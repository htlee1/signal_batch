package gc.mda.signal_batch.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitoringController {

    private final JdbcTemplate collectJdbcTemplate;
    private final JdbcTemplate queryJdbcTemplate;

    /**
     * 데이터 처리 지연 상태 확인
     */
    @GetMapping("/delay")
    public Map<String, Object> getProcessingDelay() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 수집 DB의 최신 데이터
            Map<String, Object> collectLatest = collectJdbcTemplate.queryForMap(
                """
                SELECT 
                    MAX(message_time) as latest_message_time,
                    COUNT(*) as recent_count
                FROM signal.sig_test
                WHERE message_time > NOW() - INTERVAL '10 minutes'
                """
            );
            
            // 조회 DB의 최신 처리 데이터
            Map<String, Object> queryLatest = queryJdbcTemplate.queryForMap(
                """
                SELECT 
                    MAX(time_bucket) as latest_processed_time,
                    COUNT(DISTINCT tile_id) as processed_tiles
                FROM signal.t_tile_summary
                WHERE time_bucket > NOW() - INTERVAL '10 minutes'
                """
            );
            
            LocalDateTime collectTime = (LocalDateTime) collectLatest.get("latest_message_time");
            LocalDateTime queryTime = (LocalDateTime) queryLatest.get("latest_processed_time");
            
            if (collectTime != null && queryTime != null) {
                long delayMinutes = java.time.Duration.between(queryTime, collectTime).toMinutes();
                result.put("delayMinutes", delayMinutes);
                result.put("status", delayMinutes < 10 ? "NORMAL" : delayMinutes < 30 ? "WARNING" : "CRITICAL");
            }
            
            result.put("collectLatestTime", collectTime);
            result.put("queryLatestTime", queryTime);
            result.put("recentCollectCount", collectLatest.get("recent_count"));
            result.put("processedTiles", queryLatest.get("processed_tiles"));
            
        } catch (Exception e) {
            log.error("Failed to get processing delay", e);
            result.put("error", e.getMessage());
            result.put("status", "ERROR");
        }
        
        return result;
    }

    /**
     * 대해구별 실시간 처리 현황
     */
    @GetMapping("/haegu/realtime")
    public List<Map<String, Object>> getRealtimeHaeguStatus() {
        String sql = """
            WITH recent_data AS (
                SELECT 
                    g.haegu_no,
                    t.tile_id,
                    t.tile_level,
                    t.vessel_count,
                    t.vessel_density,
                    t.time_bucket,
                    ST_X(ST_Centroid(g.tile_geom))::numeric(10,6) as center_lon,
                    ST_Y(ST_Centroid(g.tile_geom))::numeric(10,6) as center_lat
                FROM signal.t_tile_summary t
                JOIN signal.t_grid_tiles g ON t.tile_id = g.tile_id AND t.tile_level = g.tile_level
                WHERE t.time_bucket = (SELECT MAX(time_bucket) FROM signal.t_tile_summary)
            )
            SELECT 
                haegu_no,
                CONCAT('대해구 ', haegu_no) as haegu_name,
                COUNT(DISTINCT tile_id) as active_tiles,
                COALESCE(SUM(vessel_count), 0) as current_vessels,
                COALESCE(AVG(vessel_density), 0) as avg_density,
                COALESCE(MAX(vessel_count), 0) as max_tile_vessels,
                MAX(time_bucket) as last_update,
                -- 대해구 중심점 (tile_level=0인 경우만)
                MAX(CASE WHEN tile_level = 0 THEN center_lon END) as center_lon,
                MAX(CASE WHEN tile_level = 0 THEN center_lat END) as center_lat
            FROM recent_data
            WHERE tile_level = 0
            GROUP BY haegu_no
            HAVING SUM(vessel_count) > 0
            ORDER BY current_vessels DESC
            LIMIT 50
        """;
        
        try {
            return queryJdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.error("Failed to get realtime haegu status", e);
            return new ArrayList<>();
        }
    }

    /**
     * 시스템 처리량 메트릭
     */
    @GetMapping("/throughput")
    public Map<String, Object> getThroughputMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // 최근 1시간 처리량
            List<Map<String, Object>> hourlyStats = queryJdbcTemplate.queryForList(
                """
                SELECT 
                    DATE_TRUNC('minute', time_bucket) as minute,
                    COUNT(DISTINCT tile_id) as tiles_processed,
                    SUM(vessel_count) as vessels_processed
                FROM signal.t_tile_summary
                WHERE time_bucket > NOW() - INTERVAL '1 hour'
                GROUP BY DATE_TRUNC('minute', time_bucket)
                ORDER BY minute DESC
                LIMIT 60
                """
            );
            
            // 평균 처리량 계산
            if (!hourlyStats.isEmpty()) {
                long totalVessels = hourlyStats.stream()
                    .mapToLong(m -> ((Number) m.get("vessels_processed")).longValue())
                    .sum();
                double avgVesselsPerMinute = totalVessels / (double) hourlyStats.size();
                
                metrics.put("avgVesselsPerMinute", avgVesselsPerMinute);
                metrics.put("avgVesselsPerHour", avgVesselsPerMinute * 60);
                metrics.put("hourlyDetails", hourlyStats);
            }
            
            // 파티션별 크기
            List<Map<String, Object>> partitionSizes = queryJdbcTemplate.queryForList(
                """
                SELECT 
                    tablename,
                    pg_size_pretty(pg_total_relation_size('signal.' || tablename)) as size,
                    pg_total_relation_size('signal.' || tablename) as size_bytes
                FROM pg_tables
                WHERE schemaname = 'signal'
                AND tablename LIKE 't_tile_summary_%'
                AND tablename ~ '\\\\d{6}$'
                ORDER BY tablename DESC
                LIMIT 7
                """
            );
            
            metrics.put("partitionSizes", partitionSizes);
            
        } catch (Exception e) {
            log.error("Failed to get throughput metrics", e);
            metrics.put("error", e.getMessage());
        }
        
        return metrics;
    }

    /**
     * 데이터 품질 검증
     */
    @GetMapping("/quality")
    public Map<String, Object> checkDataQuality() {
        Map<String, Object> quality = new HashMap<>();
        
        try {
            // 중복 데이터 확인
            Integer duplicates = queryJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) 
                FROM (
                    SELECT tile_id, time_bucket, COUNT(*) as cnt
                    FROM signal.t_tile_summary
                    WHERE time_bucket > NOW() - INTERVAL '1 hour'
                    GROUP BY tile_id, time_bucket
                    HAVING COUNT(*) > 1
                ) dup
                """,
                Integer.class
            );
            
            // 누락된 타일 확인
            Integer missingTiles = queryJdbcTemplate.queryForObject(
                """
                WITH expected_tiles AS (
                    SELECT DISTINCT tile_id FROM signal.t_grid_tiles
                ),
                recent_tiles AS (
                    SELECT DISTINCT tile_id 
                    FROM signal.t_tile_summary
                    WHERE time_bucket > NOW() - INTERVAL '10 minutes'
                )
                SELECT COUNT(*)
                FROM expected_tiles e
                LEFT JOIN recent_tiles r ON e.tile_id = r.tile_id
                WHERE r.tile_id IS NULL
                """,
                Integer.class
            );
            
            quality.put("duplicateRecords", duplicates != null ? duplicates : 0);
            quality.put("missingTiles", missingTiles != null ? missingTiles : 0);
            quality.put("qualityScore", duplicates == 0 && missingTiles < 100 ? "GOOD" : "NEEDS_ATTENTION");
            quality.put("checkedAt", LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("Failed to check data quality", e);
            quality.put("error", e.getMessage());
            quality.put("qualityScore", "ERROR");
        }
        
        return quality;
    }
}