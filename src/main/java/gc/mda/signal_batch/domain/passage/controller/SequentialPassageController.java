package gc.mda.signal_batch.domain.passage.controller;

import gc.mda.signal_batch.domain.passage.dto.SequentialPassageRequest;
import gc.mda.signal_batch.domain.passage.dto.SequentialPassageResponse;
import gc.mda.signal_batch.domain.passage.service.SequentialAreaTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RestController
@RequestMapping("/api/v1/passages")
@RequiredArgsConstructor
@Tag(name = "Sequential Passage API", description = "순차 구역 통과 선박 조회 API")
@Validated
public class SequentialPassageController {

    private final SequentialAreaTrackingService trackingService;
    private final DataSource queryDataSource;

    @Operation(
        summary = "순차 구역 통과 선박 조회",
        description = """
            지정된 구역들을 순차적으로 통과한 선박을 조회합니다.
            - GRID: 해구(대해구/소해구) 기준으로 조회
            - AREA: 사용자 정의 구역 기준으로 조회
            
            예시:
            - 해구 93 → 92 → 100 순서로 통과한 선박
            - 구역 AREA001 → AREA002 → AREA003 순서로 통과한 선박
            """
    )
    @PostMapping("/sequential")
    public ResponseEntity<SequentialPassageResponse> getSequentialPassages(
            @Valid @RequestBody SequentialPassageRequest request) {
        
        long startMs = System.currentTimeMillis();
        log.info("Sequential passage request: type={}, zones={}, period={} to {}", 
                request.getType(), request.getZoneIds(), request.getStartTime(), request.getEndTime());
        
        // 유효성 검증
        if (request.getStartTime().isAfter(request.getEndTime())) {
            throw new IllegalArgumentException("startTime cannot be after endTime");
        }
        
        List<Map<String, Object>> results;
        
        if (request.getType() == SequentialPassageRequest.PassageType.GRID) {
            // 해구 번호로 변환
            List<Integer> haeguNumbers = request.getZoneIds().stream()
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
                    
            results = trackingService.findSequentialGridPassages(
                    haeguNumbers, request.getStartTime(), request.getEndTime());
        } else {
            results = trackingService.findSequentialAreaPassages(
                    request.getZoneIds(), request.getStartTime(), request.getEndTime());
        }
        
        // 응답 구성
        List<SequentialPassageResponse.VesselPassage> passages = results.stream()
                .map(row -> buildVesselPassage(row, request))
                .collect(Collectors.toList());
        
        long processingTime = System.currentTimeMillis() - startMs;
        
        return ResponseEntity.ok(SequentialPassageResponse.builder()
                .totalVessels(passages.size())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .zones(request.getZoneIds())
                .passages(passages)
                .processingTimeMs(processingTime)
                .build());
    }

    @Operation(
        summary = "구역 통과 통계 조회",
        description = "특정 구역의 통과 통계를 조회합니다."
    )
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getPassageStatistics(
            @Parameter(description = "구역 유형 (GRID/AREA)", required = true, example = "AREA")
            @RequestParam SequentialPassageRequest.PassageType type,
            
            @Parameter(description = "구역 ID", required = true, example = "AREA001")
            @RequestParam String zoneId,
            
            @Parameter(description = "조회 시작 시간", required = true, example = "2025-08-01T00:00:00")
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            
            @Parameter(description = "조회 종료 시간", required = true, example = "2025-08-07T23:59:59")
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime
    ) {
        log.info("Passage statistics request: type={}, zoneId={}, period={} to {}", 
                type, zoneId, startTime, endTime);
        
        Map<String, Object> stats;
        
        if (type == SequentialPassageRequest.PassageType.AREA) {
            stats = trackingService.getAreaPassageStatistics(zoneId, startTime, endTime);
        } else {
            // Grid 통계 조회
            stats = getGridPassageStatistics(Integer.parseInt(zoneId), startTime, endTime);
        }
        
        return ResponseEntity.ok(stats);
    }
    
    @Operation(
        summary = "다중 구역 동시 통과 선박 조회",
        description = """
            지정된 기간 동안 모든 구역을 통과한 선박을 조회합니다.
            (순서 관계없이 모든 구역을 방문한 선박)
            """
    )
    @PostMapping("/all-zones")
    public ResponseEntity<Map<String, Object>> getVesselsInAllZones(
            @Valid @RequestBody SequentialPassageRequest request) {
        
        log.info("All zones passage request: zones={}", request.getZoneIds());
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        String tableName = request.getType() == SequentialPassageRequest.PassageType.GRID 
                ? "t_grid_vessel_tracks" : "t_area_vessel_tracks";
        String zoneColumn = request.getType() == SequentialPassageRequest.PassageType.GRID 
                ? "haegu_no" : "area_id";
        
        String sql = String.format("""
            WITH vessel_zones AS (
                SELECT 
                    sig_src_cd,
                    target_id,
                    COUNT(DISTINCT %s) as zone_count,
                    array_agg(DISTINCT %s ORDER BY %s) as visited_zones,
                    MIN(time_bucket) as first_seen,
                    MAX(time_bucket) as last_seen,
                    SUM(distance_nm) as total_distance,
                    AVG(avg_speed) as avg_speed
                FROM signal.%s
                WHERE time_bucket BETWEEN ? AND ?
                AND %s = ANY(?)
                GROUP BY sig_src_cd, target_id
                HAVING COUNT(DISTINCT %s) = ?
            )
            SELECT * FROM vessel_zones
            ORDER BY first_seen
            """, zoneColumn, zoneColumn, zoneColumn, tableName, zoneColumn, zoneColumn);
        
        Object[] params;
        if (request.getType() == SequentialPassageRequest.PassageType.GRID) {
            Integer[] haeguArray = request.getZoneIds().stream()
                    .map(Integer::parseInt)
                    .toArray(Integer[]::new);
            params = new Object[]{
                Timestamp.valueOf(request.getStartTime()),
                Timestamp.valueOf(request.getEndTime()),
                haeguArray,
                request.getZoneIds().size()
            };
        } else {
            params = new Object[]{
                Timestamp.valueOf(request.getStartTime()),
                Timestamp.valueOf(request.getEndTime()),
                request.getZoneIds().toArray(String[]::new),
                request.getZoneIds().size()
            };
        }
        
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params);
        
        return ResponseEntity.ok(Map.of(
            "totalVessels", results.size(),
            "requiredZones", request.getZoneIds(),
            "period", Map.of(
                "start", request.getStartTime(),
                "end", request.getEndTime()
            ),
            "vessels", results
        ));
    }
    
    private SequentialPassageResponse.VesselPassage buildVesselPassage(
            Map<String, Object> row, SequentialPassageRequest request) {
        
        String sigSrcCd = (String) row.get("sig_src_cd");
        String targetId = (String) row.get("target_id");
        
        // 구역별 통과 정보 구성
        List<SequentialPassageResponse.ZonePassage> zonePassages = new ArrayList<>();
        
        for (int i = 0; i < request.getZoneIds().size(); i++) {
            String zoneId = request.getZoneIds().get(i);
            String prefix = request.getType() == SequentialPassageRequest.PassageType.GRID 
                    ? "haegu" : "area";
            
            Timestamp entryTime = (Timestamp) row.get(prefix + (i + 1) + "_entry");
            Timestamp exitTime = (Timestamp) row.get(prefix + (i + 1) + "_exit");
            
            if (entryTime != null) {
                zonePassages.add(SequentialPassageResponse.ZonePassage.builder()
                        .zoneId(zoneId)
                        .zoneName(getZoneName(zoneId, request.getType()))
                        .entryTime(entryTime.toLocalDateTime())
                        .exitTime(exitTime != null ? exitTime.toLocalDateTime() : null)
                        .build());
            }
        }
        
        // 선박 정보 조회 (캐시 활용 가능)
        SequentialPassageResponse.VesselInfo vesselInfo = getVesselInfo(sigSrcCd, targetId);
        
        return SequentialPassageResponse.VesselPassage.builder()
                .sigSrcCd(sigSrcCd)
                .targetId(targetId)
                .vesselInfo(vesselInfo)
                .zonePassages(zonePassages)
                .build();
    }
    
    private String getZoneName(String zoneId, SequentialPassageRequest.PassageType type) {
        if (type == SequentialPassageRequest.PassageType.GRID) {
            return "해구 " + zoneId;
        } else {
            return "구역 " + zoneId;
        }
    }
    
    private SequentialPassageResponse.VesselInfo getVesselInfo(String sigSrcCd, String targetId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);

        String sql = """
        SELECT ship_nm as ship_name, ship_ty as ship_type
        FROM signal.t_vessel_latest_position
        WHERE sig_src_cd = ? AND target_id = ?
        LIMIT 1
        """;

        try {
            Map<String, Object> result = jdbcTemplate.queryForMap(sql, sigSrcCd, targetId);
            return SequentialPassageResponse.VesselInfo.builder()
                    .shipName(result.get("ship_name") != null ? (String) result.get("ship_name") : null)
                    .shipType(result.get("ship_type") != null ? (String) result.get("ship_type") : null)
                    .build();
        } catch (Exception e) {
            // 데이터 없을 경우 null 반환
            return SequentialPassageResponse.VesselInfo.builder()
                    .shipName(null)
                    .shipType(null)
                    .build();
        }
    }
    
    private Map<String, Object> getGridPassageStatistics(
            Integer haeguNo, LocalDateTime startTime, LocalDateTime endTime) {
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT 
                COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as unique_vessels,
                COUNT(*) as total_passages,
                SUM(distance_nm) as total_distance,
                AVG(avg_speed) as avg_speed,
                MIN(time_bucket) as first_passage,
                MAX(time_bucket) as last_passage
            FROM signal.t_grid_vessel_tracks
            WHERE haegu_no = ?
            AND time_bucket BETWEEN ? AND ?
        """;
        
        return jdbcTemplate.queryForMap(sql,
            haeguNo,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime)
        );
    }
}