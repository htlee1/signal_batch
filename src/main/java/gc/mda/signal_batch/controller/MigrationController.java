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
}
