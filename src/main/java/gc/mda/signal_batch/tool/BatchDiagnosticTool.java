package gc.mda.signal_batch.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDiagnosticTool {

    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    /**
     * 종합 진단 실행
     */
    public DiagnosticReport runFullDiagnostic() {
        log.info("Starting batch system diagnostic...");

        DiagnosticReport report = new DiagnosticReport();
        report.setTimestamp(LocalDateTime.now());

        // 1. 데이터베이스 상태 확인
        report.setDatabaseHealth(checkDatabaseHealth());

        // 2. 파티션 상태 확인
        report.setPartitionStatus(checkPartitionStatus());

        // 3. 성능 메트릭 수집
        report.setPerformanceMetrics(collectPerformanceMetrics());

        // 4. 데이터 무결성 검사
        report.setDataIntegrity(checkDataIntegrity());

        // 5. 시스템 리소스 확인
        report.setSystemResources(checkSystemResources());

        // 6. 권장사항 생성
        report.setRecommendations(generateRecommendations(report));

        log.info("Diagnostic completed");
        return report;
    }

    /**
     * 데이터베이스 상태 확인
     */
    private DatabaseHealth checkDatabaseHealth() {
        DatabaseHealth health = new DatabaseHealth();

        // 연결 상태
        health.setCollectDbConnected(testConnection(collectJdbcTemplate));
        health.setQueryDbConnected(testConnection(queryJdbcTemplate));

        // 활성 연결 수
        health.setActiveConnections(getActiveConnections());

        // 느린 쿼리
        health.setSlowQueries(getSlowQueries());

        // 테이블 크기
        health.setTableSizes(getTableSizes());

        // Lock 상황
        health.setLockInfo(getLockInfo());

        return health;
    }

    /**
     * 파티션 상태 확인
     */
    private PartitionStatus checkPartitionStatus() {
        PartitionStatus status = new PartitionStatus();

        String sql = """
            SELECT 
                tablename,
                pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
                pg_total_relation_size(schemaname||'.'||tablename) as size_bytes
            FROM pg_tables
            WHERE schemaname = 'signal' 
              AND tablename LIKE 'sig_test_%'
            ORDER BY tablename
        """;

        List<Map<String, Object>> partitions = collectJdbcTemplate.queryForList(sql);

        status.setTotalPartitions(partitions.size());
        status.setPartitionDetails(partitions);

        // 미래 파티션 확인
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        String tomorrowPartition = "sig_test_" +
                tomorrow.format(DateTimeFormatter.BASIC_ISO_DATE);

        status.setHasFuturePartitions(
                partitions.stream().anyMatch(p -> p.get("tablename").equals(tomorrowPartition))
        );

        // 가장 큰 파티션
        partitions.stream()
                .max(Comparator.comparing(p -> (Long) p.get("size_bytes")))
                .ifPresent(p -> status.setLargestPartition(p));

        return status;
    }

    /**
     * 성능 메트릭 수집
     */
    private PerformanceMetrics collectPerformanceMetrics() {
        PerformanceMetrics metrics = new PerformanceMetrics();

        // 처리 속도
        Map<String, Object> throughput = queryJdbcTemplate.queryForMap("""
            SELECT 
                COUNT(*) as records_last_hour,
                COUNT(*) / 3600.0 as records_per_second
            FROM signal.t_vessel_latest_position
            WHERE last_update > NOW() - INTERVAL '1 hour'
        """);

        metrics.setRecordsLastHour(((Number) throughput.get("records_last_hour")).longValue());
        metrics.setRecordsPerSecond(((Number) throughput.get("records_per_second")).doubleValue());

        // 인덱스 효율성
        List<Map<String, Object>> indexStats = collectJdbcTemplate.queryForList("""
            SELECT 
                indexrelname,
                idx_scan,
                idx_tup_read,
                idx_tup_fetch,
                pg_size_pretty(pg_relation_size(indexrelid)) as size
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal'
              AND idx_scan > 0
            ORDER BY idx_scan DESC
            LIMIT 10
        """);

        metrics.setIndexEfficiency(indexStats);

        // 캐시 히트율
        Map<String, Object> cacheStats = collectJdbcTemplate.queryForMap("""
            SELECT 
                sum(heap_blks_read) as heap_read,
                sum(heap_blks_hit) as heap_hit,
                CASE WHEN sum(heap_blks_hit) + sum(heap_blks_read) > 0 THEN
                    sum(heap_blks_hit)::float / (sum(heap_blks_hit) + sum(heap_blks_read))
                ELSE 0 END as cache_hit_ratio
            FROM pg_statio_user_tables
        """);

        metrics.setCacheHitRatio(((Number) cacheStats.get("cache_hit_ratio")).doubleValue());

        return metrics;
    }

    /**
     * 데이터 무결성 검사
     */
    private DataIntegrity checkDataIntegrity() {
        DataIntegrity integrity = new DataIntegrity();

        // 중복 데이터 확인
        Long duplicates = collectJdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM (
                SELECT sig_src_cd, target_id, message_time, COUNT(*)
                FROM signal.sig_test
                WHERE message_time > NOW() - INTERVAL '1 day'
                AND sig_src_cd != '000005'
                AND length(target_id) > 5
                GROUP BY sig_src_cd, target_id, message_time
                HAVING COUNT(*) > 1
            ) dup
        """, Long.class);

        integrity.setDuplicateRecords(duplicates);

        // 누락된 시간대 확인
        List<String> missingHours = collectJdbcTemplate.queryForList("""
            WITH hours AS (
                SELECT generate_series(
                    NOW() - INTERVAL '24 hours',
                    NOW(),
                    INTERVAL '1 hour'
                ) as hour
            )
            SELECT TO_CHAR(h.hour, 'YYYY-MM-DD HH24:00') as missing_hour
            FROM hours h
            LEFT JOIN (
                SELECT DATE_TRUNC('hour', message_time) as data_hour
                FROM signal.sig_test
                WHERE message_time > NOW() - INTERVAL '24 hours'
                GROUP BY DATE_TRUNC('hour', message_time)
            ) d ON h.hour = d.data_hour
            WHERE d.data_hour IS NULL
        """, String.class);

        integrity.setMissingTimeRanges(missingHours);

        // 데이터 지연 확인
        LocalDateTime latestData = collectJdbcTemplate.queryForObject(
                "SELECT MAX(message_time) FROM signal.sig_test",
                LocalDateTime.class
        );

        if (latestData != null) {
            long delayMinutes = java.time.Duration.between(latestData, LocalDateTime.now()).toMinutes();
            integrity.setDataDelayMinutes(delayMinutes);
        }

        return integrity;
    }

    /**
     * 시스템 리소스 확인
     */
    private SystemResources checkSystemResources() {
        SystemResources resources = new SystemResources();

        Runtime runtime = Runtime.getRuntime();

        // JVM 메모리
        resources.setMaxMemory(runtime.maxMemory());
        resources.setTotalMemory(runtime.totalMemory());
        resources.setFreeMemory(runtime.freeMemory());
        resources.setUsedMemory(runtime.totalMemory() - runtime.freeMemory());

        // CPU
        resources.setAvailableProcessors(runtime.availableProcessors());

        // 스레드
        resources.setActiveThreads(Thread.activeCount());

        // 디스크 공간 (데이터베이스)
        Map<String, Object> diskSpace = collectJdbcTemplate.queryForMap("""
            SELECT 
                pg_database_size(current_database()) as db_size,
                pg_size_pretty(pg_database_size(current_database())) as db_size_pretty
        """);

        resources.setDatabaseSize((Long) diskSpace.get("db_size"));
        resources.setDatabaseSizePretty((String) diskSpace.get("db_size_pretty"));

        return resources;
    }

    /**
     * 권장사항 생성
     */
    private List<String> generateRecommendations(DiagnosticReport report) {
        List<String> recommendations = new ArrayList<>();

        // 메모리 사용률 확인
        SystemResources resources = report.getSystemResources();
        double memoryUsage = (double) resources.getUsedMemory() / resources.getMaxMemory();
        if (memoryUsage > 0.8) {
            recommendations.add("High memory usage detected (" +
                    String.format("%.1f%%", memoryUsage * 100) +
                    "). Consider increasing heap size or optimizing memory usage.");
        }

        // 캐시 히트율 확인
        PerformanceMetrics metrics = report.getPerformanceMetrics();
        if (metrics.getCacheHitRatio() < 0.9) {
            recommendations.add("Low cache hit ratio (" +
                    String.format("%.1f%%", metrics.getCacheHitRatio() * 100) +
                    "). Consider increasing shared_buffers or optimizing queries.");
        }

        // 데이터 지연 확인
        DataIntegrity integrity = report.getDataIntegrity();
        if (integrity.getDataDelayMinutes() > 30) {
            recommendations.add("Data processing delay detected (" +
                    integrity.getDataDelayMinutes() + " minutes). Check job execution status.");
        }

        // 파티션 확인
        PartitionStatus partitionStatus = report.getPartitionStatus();
        if (!partitionStatus.isHasFuturePartitions()) {
            recommendations.add("No future partitions found. Run partition creation job.");
        }

        // 중복 데이터 확인
        if (integrity.getDuplicateRecords() > 0) {
            recommendations.add("Found " + integrity.getDuplicateRecords() +
                    " duplicate records. Review data ingestion process.");
        }

        // 느린 쿼리 확인
        DatabaseHealth dbHealth = report.getDatabaseHealth();
        if (!dbHealth.getSlowQueries().isEmpty()) {
            recommendations.add("Found " + dbHealth.getSlowQueries().size() +
                    " slow queries. Consider query optimization or index creation.");
        }

        return recommendations;
    }

    // Helper 메소드들
    private boolean testConnection(JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int getActiveConnections() {
        return collectJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'",
                Integer.class
        );
    }

    private List<Map<String, Object>> getSlowQueries() {
        return collectJdbcTemplate.queryForList("""
            SELECT 
                query,
                mean_exec_time,
                calls,
                total_exec_time
            FROM pg_stat_statements
            WHERE mean_exec_time > 1000
            ORDER BY mean_exec_time DESC
            LIMIT 10
        """);
    }

    private List<Map<String, Object>> getTableSizes() {
        return collectJdbcTemplate.queryForList("""
            SELECT 
                schemaname,
                tablename,
                pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
            FROM pg_tables
            WHERE schemaname = 'signal'
            ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
            LIMIT 10
        """);
    }

    private List<Map<String, Object>> getLockInfo() {
        return collectJdbcTemplate.queryForList("""
            SELECT 
                pid,
                locktype,
                mode,
                granted
            FROM pg_locks
            WHERE locktype NOT IN ('virtualxid', 'relation')
            LIMIT 20
        """);
    }

    // 리포트 클래스들
    @lombok.Data
    public static class DiagnosticReport {
        private LocalDateTime timestamp;
        private DatabaseHealth databaseHealth;
        private PartitionStatus partitionStatus;
        private PerformanceMetrics performanceMetrics;
        private DataIntegrity dataIntegrity;
        private SystemResources systemResources;
        private List<String> recommendations;
    }

    @lombok.Data
    public static class DatabaseHealth {
        private boolean collectDbConnected;
        private boolean queryDbConnected;
        private int activeConnections;
        private List<Map<String, Object>> slowQueries;
        private List<Map<String, Object>> tableSizes;
        private List<Map<String, Object>> lockInfo;
    }

    @lombok.Data
    public static class PartitionStatus {
        private int totalPartitions;
        private boolean hasFuturePartitions;
        private Map<String, Object> largestPartition;
        private List<Map<String, Object>> partitionDetails;
    }

    @lombok.Data
    public static class PerformanceMetrics {
        private long recordsLastHour;
        private double recordsPerSecond;
        private double cacheHitRatio;
        private List<Map<String, Object>> indexEfficiency;
    }

    @lombok.Data
    public static class DataIntegrity {
        private long duplicateRecords;
        private List<String> missingTimeRanges;
        private long dataDelayMinutes;
    }

    @lombok.Data
    public static class SystemResources {
        private long maxMemory;
        private long totalMemory;
        private long freeMemory;
        private long usedMemory;
        private int availableProcessors;
        private int activeThreads;
        private long databaseSize;
        private String databaseSizePretty;
    }
}