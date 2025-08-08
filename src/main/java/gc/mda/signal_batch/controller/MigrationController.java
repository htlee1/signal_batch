package gc.mda.signal_batch.controller;

import gc.mda.signal_batch.migration.unix_timestamp.MissingDataFiller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * MIGRATION_V2: Unix timestamp 마이그레이션 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class MigrationController {
    
    private final MissingDataFiller missingDataFiller;
    
    @PostMapping("/fill-hourly")
    public ResponseEntity<Map<String, Object>> fillHourlyData(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime timeBucket) {
        
        try {
            int count = missingDataFiller.fillMissingHourlyData(timeBucket);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", String.format("Filled %d missing hourly records for %s", count, timeBucket),
                "count", count
            ));
        } catch (Exception e) {
            log.error("Failed to fill hourly data", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    @PostMapping("/fill-daily")
    public ResponseEntity<Map<String, Object>> fillDailyData(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        
        try {
            int count = missingDataFiller.fillMissingDailyData(date.atStartOfDay());
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", String.format("Filled %d missing daily records for %s", count, date),
                "count", count
            ));
        } catch (Exception e) {
            log.error("Failed to fill daily data", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        
        String sql = """
            SELECT 
                '5min' as type,
                COUNT(*) as total,
                COUNT(track_geom) as v1_count,
                COUNT(track_geom_v2) as v2_count
            FROM signal.t_vessel_tracks_5min
            WHERE DATE(time_bucket) = ?
            """;
        
        // 상태 조회 로직
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "date", date.toString()
        ));
    }
    
    /**
     * Unix timestamp 검증 및 자동 수정
     * - track_geom_v2가 비어있으면 채우기
     * - M값이 잘못되었으면(9시간 차이) 수정
     */
    @PostMapping("/fix-unix-timestamps")
    public ResponseEntity<Map<String, Object>> fixUnixTimestamps(
            @RequestParam String tableName,  // "5min", "hourly", "daily"
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(defaultValue = "1000") int batchSize) {
        
        try {
            FixResult result = missingDataFiller.fixUnixTimestamps(tableName, startTime, endTime, batchSize);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "processed", result.processedCount,
                "filled", result.filledCount,
                "corrected", result.correctedCount,
                "skipped", result.skippedCount,
                "lastTimeBucket", result.lastTimeBucket != null ? result.lastTimeBucket.toString() : "none",
                "message", String.format("Processed %d time buckets: filled=%d, corrected=%d, skipped=%d",
                    result.processedCount, result.filledCount, result.correctedCount, result.skippedCount)
            ));
        } catch (Exception e) {
            log.error("Failed to fix unix timestamps", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Unix timestamp 검증 (수정 없이 확인만)
     */
    @GetMapping("/verify-unix-timestamps")
    public ResponseEntity<Map<String, Object>> verifyUnixTimestamps(
            @RequestParam String tableName,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime timeBucket) {
        
        try {
            VerifyResult result = missingDataFiller.verifyUnixTimestamps(tableName, timeBucket);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "tableName", tableName,
                "timeBucket", timeBucket.toString(),
                "totalRecords", result.totalRecords,
                "emptyV2", result.emptyV2Count,
                "correctV2", result.correctV2Count,
                "incorrectV2", result.incorrectV2Count,
                "samples", result.samples
            ));
        } catch (Exception e) {
            log.error("Failed to verify unix timestamps", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    public static class FixResult {
        public int processedCount;
        public int filledCount;
        public int correctedCount;
        public int skippedCount;
        public int createdBuckets;
        public LocalDateTime lastTimeBucket;
    }
    
    public static class VerifyResult {
        public int totalRecords;
        public int emptyV2Count;
        public int correctV2Count;
        public int incorrectV2Count;
        public Map<String, Object>[] samples;
    }
}
