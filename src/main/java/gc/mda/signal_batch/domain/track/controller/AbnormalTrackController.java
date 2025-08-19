package gc.mda.signal_batch.domain.track.controller;

import gc.mda.signal_batch.domain.track.dto.AbnormalTrackResponse;
import gc.mda.signal_batch.domain.track.dto.AbnormalTrackStatsResponse;
import gc.mda.signal_batch.domain.track.service.AbnormalTrackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;


/**
 * 비정상 궤적 모니터링 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/abnormal-tracks")
@RequiredArgsConstructor
@Tag(name = "비정상 항적 검출 API", description = "비정상 항적 검출, 조회 및 통계 API")
public class AbnormalTrackController {
    
    private final AbnormalTrackService abnormalTrackService;
    
    @GetMapping("/recent")
    @Operation(summary = "최근 비정상 항적 조회", description = "지정된 시간 이내의 비정상 항적을 조회합니다.")
    public ResponseEntity<List<AbnormalTrackResponse>> getRecentAbnormalTracks(
            @Parameter(description = "조회 시간 (기본값: 24시간)")
            @RequestParam(defaultValue = "24") int hours) {
        
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<AbnormalTrackResponse> tracks = abnormalTrackService.getAbnormalTracksSince(since);
        
        return ResponseEntity.ok(tracks);
    }
    
    @GetMapping("/vessel/{sigSrcCd}/{targetId}")
    @Operation(summary = "특정 선박의 비정상 항적 이력", description = "특정 선박의 비정상 항적 이력을 조회합니다.")
    public ResponseEntity<List<AbnormalTrackResponse>> getVesselAbnormalTracks(
            @PathVariable String sigSrcCd,
            @PathVariable String targetId,
            @Parameter(description = "시작 날짜")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료 날짜")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        List<AbnormalTrackResponse> tracks = abnormalTrackService.getVesselAbnormalTracks(
            sigSrcCd, targetId, startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay()
        );
        
        return ResponseEntity.ok(tracks);
    }
    
    @GetMapping("/statistics")
    @Operation(summary = "비정상 항적 통계", description = "지정된 기간의 비정상 항적 통계를 조회합니다.")
    public ResponseEntity<List<AbnormalTrackStatsResponse>> getAbnormalTrackStatistics(
            @Parameter(description = "시작 날짜")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료 날짜")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        List<AbnormalTrackStatsResponse> stats = abnormalTrackService.getStatistics(startDate, endDate);
        
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/statistics/summary")
    @Operation(summary = "비정상 항적 요약 통계", description = "비정상 유형별 요약 통계를 조회합니다.")
    public ResponseEntity<Map<String, Object>> getAbnormalTrackSummary(
            @Parameter(description = "조회 일수 (기본값: 7일)")
            @RequestParam(defaultValue = "7") int days) {
        
        Map<String, Object> summary = abnormalTrackService.getSummaryStatistics(days);
        
        return ResponseEntity.ok(summary);
    }
    
    @GetMapping("/types")
    @Operation(summary = "비정상 유형 목록", description = "비정상 항적 유형 목록을 조회합니다.")
    public ResponseEntity<Map<String, String>> getAbnormalTypes() {
        Map<String, String> types = Map.of(
            "excessive_speed", "과속 (50 knots 초과)",
            "teleport", "순간이동 (5분간 10nm 초과)",
            "gap_jump", "Bucket 간 비정상 이동",
            "excessive_acceleration", "급가속 (분당 10 knots 초과)"
        );
        
        return ResponseEntity.ok(types);
    }
    
    @PostMapping("/detect")
    @Operation(summary = "사용자 정의 기준으로 비정상 항적 검출", description = "hourly/daily 테이블에서 거리/속도 기준으로 비정상 항적을 검출합니다.")
    public ResponseEntity<List<AbnormalTrackResponse>> detectAbnormalTracks(
            @RequestBody DetectRequest request) {
        
        List<AbnormalTrackResponse> tracks = abnormalTrackService.detectFromHourlyDaily(
            request.getTableType(),
            request.getStartTime(),
            request.getEndTime(),
            request.getMinDistance(),
            request.getMinSpeed()
        );
        
        return ResponseEntity.ok(tracks);
    }
    
    @PostMapping("/move-to-abnormal")
    @Operation(summary = "선택된 항적을 비정상 테이블로 이동", description = "선택된 항적을 원래 테이블에서 삭제하고 t_abnormal_tracks로 이동합니다.")
    public ResponseEntity<Map<String, Object>> moveToAbnormalTracks(
            @RequestBody MoveTracksRequest request) {
        
        int movedCount = abnormalTrackService.moveToAbnormalTracks(
            request.getTableType(),
            request.getTracks(),
            request.getAbnormalType(),
            request.getReason()
        );
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "movedCount", movedCount,
            "message", String.format("%d개의 항적이 비정상 테이블로 이동되었습니다.", movedCount)
        ));
    }
    
    @lombok.Data
    static class DetectRequest {
        private String tableType; // "hourly" or "daily"
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime startTime;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime endTime;
        private BigDecimal minDistance; // 최소 거리 (nm)
        private BigDecimal minSpeed; // 최소 평균속도 (knots)
    }
    
    @lombok.Data
    static class MoveTracksRequest {
        private String tableType; // "hourly" or "daily"
        private List<TrackIdentifier> tracks;
        private String abnormalType;
        private String reason;
    }
    
    @lombok.Data
    public static class TrackIdentifier {
        private String sigSrcCd;
        private String targetId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timeBucket;
    }
    
    @DeleteMapping("/cleanup")
    @Operation(summary = "오래된 비정상 항적 정리", description = "지정된 일수 이전의 비정상 항적 데이터를 삭제합니다.")
    public ResponseEntity<Map<String, Object>> cleanupOldAbnormalTracks(
            @Parameter(description = "보존 기간 (일)")
            @RequestParam(defaultValue = "30") int retentionDays) {
        
        int deletedCount = abnormalTrackService.cleanupOldData(retentionDays);
        
        return ResponseEntity.ok(Map.of(
            "deletedCount", deletedCount,
            "retentionDays", retentionDays,
            "message", String.format("%d일 이전의 %d건 비정상 항적 데이터가 삭제되었습니다.", retentionDays, deletedCount)
        ));
    }
}