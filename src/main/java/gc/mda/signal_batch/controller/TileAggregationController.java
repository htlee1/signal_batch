package gc.mda.signal_batch.controller;

import gc.mda.signal_batch.dto.TileAggregationRequest;
import gc.mda.signal_batch.dto.TileAggregationResponse;
import gc.mda.signal_batch.service.TileAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tiles")
@RequiredArgsConstructor
@Tag(name = "Tile Aggregation API", description = "대해구/소해구 기반 선박 집계 데이터 조회 API")
@Validated
public class TileAggregationController {

    private final TileAggregationService tileAggregationService;

    @Operation(
        summary = "타일별 선박 집계 조회",
        description = """
            지정된 기간 동안의 타일별 선박 집계 정보를 조회합니다.
            - tile_id가 지정된 경우: 해당 타일(대해구/소해구)의 상세 정보 반환
            - tile_id가 없는 경우: 전체 대해구 요약 정보 반환
            
            중복 제거 정책:
            - 동일 선박이 여러 시간대에 나타날 경우 최신 위치만 포함
            - unique_vessels에는 각 선박의 마지막 위치 정보가 포함됨
            """
    )
    @GetMapping("/aggregation")
    public ResponseEntity<TileAggregationResponse> getTileAggregation(
            @Parameter(description = "조회 시작 시간 (yyyy-MM-dd'T'HH:mm:ss)", required = true, example = "2025-01-18T00:00:00")
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime fromDate,
            
            @Parameter(description = "조회 종료 시간 (yyyy-MM-dd'T'HH:mm:ss)", required = true, example = "2025-01-18T23:59:59")
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime toDate,
            
            @Parameter(description = "타일 ID (대해구: H238, 소해구: H238_S5)", required = false, example = "H238")
            @RequestParam(required = false)
            String tileId
    ) {
        log.info("Tile aggregation request - fromDate: {}, toDate: {}, tileId: {}", fromDate, toDate, tileId);
        
        // 유효성 검증
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate cannot be after toDate");
        }
        
        TileAggregationRequest request = TileAggregationRequest.builder()
                .fromDate(fromDate)
                .toDate(toDate)
                .tileId(tileId)
                .build();
                
        TileAggregationResponse response = tileAggregationService.getTileAggregation(request);
        
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "특정 타일의 시계열 집계 조회",
        description = "지정된 타일의 시간대별 선박 변화 추이를 조회합니다."
    )
    @GetMapping("/aggregation/{tileId}/timeseries")
    public ResponseEntity<Object> getTileTimeSeries(
            @Parameter(description = "타일 ID", required = true, example = "H238")
            @PathVariable String tileId,
            
            @Parameter(description = "조회 시작 시간", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime fromDate,
            
            @Parameter(description = "조회 종료 시간", required = true)
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime toDate,
            
            @Parameter(description = "집계 간격 (분)", required = false, example = "60")
            @RequestParam(defaultValue = "60") 
            Integer intervalMinutes
    ) {
        log.info("Tile timeseries request - tileId: {}, fromDate: {}, toDate: {}, interval: {} minutes", 
                tileId, fromDate, toDate, intervalMinutes);
        
        // TODO: 시계열 데이터 조회 서비스 구현
        return ResponseEntity.ok(Map.of(
            "message", "Time series endpoint - To be implemented",
            "tileId", tileId,
            "fromDate", fromDate,
            "toDate", toDate,
            "intervalMinutes", intervalMinutes
        ));
    }

    @Operation(
        summary = "타일 정보 조회",
        description = "타일 ID로 타일의 메타데이터(대해구/소해구 정보, 경계 좌표 등)를 조회합니다."
    )
    @GetMapping("/info/{tileId}")
    public ResponseEntity<Object> getTileInfo(
            @Parameter(description = "타일 ID", required = true, example = "H238")
            @PathVariable String tileId
    ) {
        log.info("Tile info request - tileId: {}", tileId);
        
        return ResponseEntity.ok(tileAggregationService.getTileInfo(tileId));
    }
}
