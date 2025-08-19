package gc.mda.signal_batch.monitoring.health;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class BatchHealthIndicator implements HealthIndicator {

    private final JobExplorer jobExplorer;

    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    @Value("${vessel.batch.health.job-timeout-hours:6}")
    private int jobTimeoutHours;

    @Value("${vessel.batch.health.min-partition-count:1}")
    private int minPartitionCount;

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();

            // 1. Job 실행 상태 확인
            JobHealthStatus jobStatus = checkJobHealth();
            details.put("jobs", jobStatus);

            // 2. 데이터베이스 연결 상태
            DatabaseHealthStatus dbStatus = checkDatabaseHealth();
            details.put("databases", dbStatus);

            // 3. 파티션 상태
            PartitionHealthStatus partitionStatus = checkPartitionHealth();
            details.put("partitions", partitionStatus);

            // 4. 시스템 리소스
            SystemResourceStatus resourceStatus = checkSystemResources();
            details.put("resources", resourceStatus);

            // 5. 최근 처리 통계
            ProcessingStatistics statistics = getProcessingStatistics();
            details.put("statistics", statistics);

            // 전체 상태 판단
            Health.Builder builder = determineOverallHealth(
                    jobStatus, dbStatus, partitionStatus, resourceStatus
            );

            return builder.withDetails(details).build();

        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .build();
        }
    }

    private JobHealthStatus checkJobHealth() {
        JobHealthStatus status = new JobHealthStatus();

        try {
            // 실행 중인 Job 확인
            List<String> jobNames = jobExplorer.getJobNames();
            int runningJobs = 0;
            int stuckJobs = 0;

            for (String jobName : jobNames) {
                Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
                runningJobs += runningExecutions.size();

                // 타임아웃된 Job 확인
                for (JobExecution execution : runningExecutions) {
                    if (execution.getStartTime() != null) {
                        LocalDateTime startTime = execution.getStartTime();
                        if (startTime.isBefore(LocalDateTime.now().minusHours(jobTimeoutHours))) {
                            stuckJobs++;
                        }
                    }
                }
            }

            // 최근 실패한 Job
            int recentFailures = countRecentFailures();

            status.setRunningJobs(runningJobs);
            status.setStuckJobs(stuckJobs);
            status.setRecentFailures(recentFailures);
            status.setHealthy(stuckJobs == 0 && recentFailures < 5);

        } catch (Exception e) {
            status.setHealthy(false);
            status.setError(e.getMessage());
        }

        return status;
    }

    private DatabaseHealthStatus checkDatabaseHealth() {
        DatabaseHealthStatus status = new DatabaseHealthStatus();

        // Collect DB 상태
        status.setCollectDb(checkDatabase("collect", collectJdbcTemplate));

        // Query DB 상태
        status.setQueryDb(checkDatabase("query", queryJdbcTemplate));

        // 전체 상태
        status.setHealthy(status.getCollectDb().isHealthy() && status.getQueryDb().isHealthy());

        return status;
    }

    private DatabaseStatus checkDatabase(String name, JdbcTemplate jdbcTemplate) {
        DatabaseStatus status = new DatabaseStatus();
        status.setName(name);

        try {
            // 연결 테스트
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            status.setConnected(true);

            // 활성 연결 수
            Integer activeConnections = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'",
                    Integer.class
            );
            status.setActiveConnections(activeConnections);

            // 데이터베이스 크기
            Long dbSize = jdbcTemplate.queryForObject(
                    "SELECT pg_database_size(current_database())",
                    Long.class
            );
            status.setDatabaseSize(dbSize);

            // 가장 오래된 트랜잭션
            // pg_stat_activity.xact_start는 TIMESTAMPTZ 타입이므로 timestamp로 캐스팅
            LocalDateTime oldestTransaction = jdbcTemplate.queryForObject(
                    "SELECT MIN(xact_start AT TIME ZONE 'Asia/Seoul')::timestamp FROM pg_stat_activity WHERE state != 'idle'",
                    LocalDateTime.class
            );

            if (oldestTransaction != null) {
                long ageMinutes = java.time.Duration.between(
                        oldestTransaction, LocalDateTime.now()
                ).toMinutes();
                status.setOldestTransactionAge(ageMinutes);
            }

            status.setHealthy(activeConnections < 100 &&
                    (oldestTransaction == null || status.getOldestTransactionAge() < 60));

        } catch (Exception e) {
            status.setConnected(false);
            status.setHealthy(false);
            status.setError(e.getMessage());
        }

        return status;
    }

    private PartitionHealthStatus checkPartitionHealth() {
        PartitionHealthStatus status = new PartitionHealthStatus();

        try {
            // collectDB의 원본 파티션 수 확인 - YYMMDD 형식 (6자리)
            Integer collectPartitionCount = collectJdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) 
                    FROM pg_tables 
                    WHERE schemaname = 'signal' 
                      AND tablename LIKE 'sig_test_%'
                      AND tablename ~ '\\\\d{6}$'
                    """,
                    Integer.class
            );
            
            // queryDB의 집계 파티션 수 확인
            Integer queryPartitionCount = queryJdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) 
                    FROM pg_tables 
                    WHERE schemaname = 'signal' 
                      AND (tablename LIKE 't_tile_summary_%' OR tablename LIKE 't_area_statistics_%')
                      AND tablename ~ '\\\\d{6}$'
                    """,
                    Integer.class
            );
            
            status.setCurrentPartitions(collectPartitionCount != null ? collectPartitionCount : 0);
            status.setQueryPartitions(queryPartitionCount != null ? queryPartitionCount : 0);

            // collectDB의 미래 파티션 확인 - YYMMDD 형식
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            String tomorrowPartition = "sig_test_" +
                    tomorrow.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));

            Boolean hasFuturePartition = collectJdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'signal' AND tablename = ?)",
                    Boolean.class, tomorrowPartition
            );

            status.setHasFuturePartitions(Boolean.TRUE.equals(hasFuturePartition));

            // collectDB의 가장 큰 파티션
            try {
                Map<String, Object> largestPartition = collectJdbcTemplate.queryForMap(
                        """
                        SELECT 
                            tablename,
                            pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
                        FROM pg_tables
                        WHERE schemaname = 'signal' 
                          AND tablename LIKE 'sig_test_%'
                        ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
                        LIMIT 1
                        """
                );
                status.setLargestPartition(largestPartition);
            } catch (Exception e) {
                status.setLargestPartition(new HashMap<>());
            }

            // 파티션이 하나도 없어도 healthy로 처리 (초기 상태)
            status.setHealthy(true);

        } catch (Exception e) {
            status.setHealthy(false);
            status.setError(e.getMessage());
        }

        return status;
    }

    private SystemResourceStatus checkSystemResources() {
        SystemResourceStatus status = new SystemResourceStatus();

        Runtime runtime = Runtime.getRuntime();

        // 메모리 상태
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        status.setMemoryUsed(usedMemory);
        status.setMemoryMax(maxMemory);
        status.setMemoryUsagePercent((double) usedMemory / maxMemory * 100);

        // CPU 정보
        status.setAvailableProcessors(runtime.availableProcessors());

        // 스레드 정보
        status.setActiveThreads(Thread.activeCount());

        status.setHealthy(status.getMemoryUsagePercent() < 90);

        return status;
    }

    private ProcessingStatistics getProcessingStatistics() {
        ProcessingStatistics stats = new ProcessingStatistics();

        try {
            // 테이블 존재 여부 확인
            Boolean tableExists = queryJdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'signal' AND tablename = 't_vessel_latest_position')",
                    Boolean.class
            );

            if (Boolean.TRUE.equals(tableExists)) {
                // 테이블에 데이터가 있는지 확인
                Integer rowCount = queryJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM signal.t_vessel_latest_position LIMIT 1",
                        Integer.class
                );

                if (rowCount != null && rowCount > 0) {
                    // 최근 1시간 처리량
                    Map<String, Object> recentStats = queryJdbcTemplate.queryForMap(
                            """
                            SELECT 
                                COALESCE(COUNT(*), 0) as total_records,
                                COALESCE(COUNT(DISTINCT sig_src_cd || ':' || target_id), 0) as unique_vessels,
                                COALESCE(AVG(update_count), 0.0) as avg_updates
                            FROM signal.t_vessel_latest_position
                            WHERE last_update > NOW() - INTERVAL '1 hour'
                            """
                    );

                    // Null 체크 추가
                    Object totalRecords = recentStats.get("total_records");
                    stats.setRecentRecords(totalRecords != null ? ((Number) totalRecords).longValue() : 0L);
                    
                    Object uniqueVessels = recentStats.get("unique_vessels");
                    stats.setUniqueVessels(uniqueVessels != null ? ((Number) uniqueVessels).longValue() : 0L);
                    
                    Object avgUpdates = recentStats.get("avg_updates");
                    stats.setAvgUpdatesPerVessel(avgUpdates != null ? ((Number) avgUpdates).doubleValue() : 0.0);

                    // 처리 지연
                    LocalDateTime latestProcessed = queryJdbcTemplate.queryForObject(
                            "SELECT MAX(last_update) FROM signal.t_vessel_latest_position",
                            LocalDateTime.class
                    );

                    if (latestProcessed != null) {
                        long delayMinutes = java.time.Duration.between(
                                latestProcessed, LocalDateTime.now()
                        ).toMinutes();
                        stats.setProcessingDelayMinutes(delayMinutes);
                    }
                }
            }

        } catch (Exception e) {
            // 테이블이 없거나 다른 문제가 있을 수 있음 - 에러는 무시하고 기본값 사용
            log.debug("Failed to get processing statistics: {}", e.getMessage());
        }

        return stats;
    }

    private Health.Builder determineOverallHealth(
            JobHealthStatus jobStatus,
            DatabaseHealthStatus dbStatus,
            PartitionHealthStatus partitionStatus,
            SystemResourceStatus resourceStatus) {

        if (!dbStatus.isHealthy()) {
            return Health.down().withDetail("reason", "Database connection issues");
        }

        if (jobStatus.getStuckJobs() > 0) {
            return Health.down().withDetail("reason", "Stuck jobs detected");
        }

        // 파티션 문제는 OUT_OF_SERVICE가 아닌 경고 수준으로 처리
        // if (!partitionStatus.isHealthy()) {
        //     return Health.outOfService().withDetail("reason", "Partition issues");
        // }

        if (!resourceStatus.isHealthy()) {
            return Health.outOfService().withDetail("reason", "High resource usage");
        }

        if (jobStatus.getRecentFailures() > 5) {
            return Health.down().withDetail("reason", "High failure rate");
        }

        return Health.up();
    }

    private int countRecentFailures() {
        // 최근 1시간 내 실패한 Job 수 조회 로직
        return 0; // 실제 구현 필요
    }

    // slf4j 로거 추가
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BatchHealthIndicator.class);

    // 내부 클래스들
    @lombok.Data
    private static class JobHealthStatus {
        private int runningJobs;
        private int stuckJobs;
        private int recentFailures;
        private boolean healthy;
        private String error;
    }

    @lombok.Data
    private static class DatabaseHealthStatus {
        private DatabaseStatus collectDb;
        private DatabaseStatus queryDb;
        private boolean healthy;
    }

    @lombok.Data
    private static class DatabaseStatus {
        private String name;
        private boolean connected;
        private int activeConnections;
        private long databaseSize;
        private long oldestTransactionAge;
        private boolean healthy;
        private String error;
    }

    @lombok.Data
    private static class PartitionHealthStatus {
        private int currentPartitions;
        private int queryPartitions;  // 추가
        private boolean hasFuturePartitions;
        private Map<String, Object> largestPartition;
        private boolean healthy;
        private String error;
    }

    @lombok.Data
    private static class SystemResourceStatus {
        private long memoryUsed;
        private long memoryMax;
        private double memoryUsagePercent;
        private int availableProcessors;
        private int activeThreads;
        private boolean healthy;
    }

    @lombok.Data
    private static class ProcessingStatistics {
        private long recentRecords;
        private long uniqueVessels;
        private double avgUpdatesPerVessel;
        private long processingDelayMinutes;
    }
}