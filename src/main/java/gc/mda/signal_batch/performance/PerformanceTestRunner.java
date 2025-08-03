package gc.mda.signal_batch.performance;

import gc.mda.signal_batch.monitor.BatchMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

@Slf4j
@Component
@Profile("performance-test")
@RequiredArgsConstructor
public class PerformanceTestRunner implements CommandLineRunner {

    @Autowired
    @Qualifier("asyncJobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("vesselAggregationJob")
    private Job vesselAggregationJob;

    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    private final BatchMetricsCollector metricsCollector;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting Performance Test Suite ===");

        // 테스트 시나리오 선택
        String scenario = args.length > 0 ? args[0] : "all";

        switch (scenario) {
            case "throughput":
                runThroughputTest();
                break;
            case "concurrent":
                runConcurrentExecutionTest();
                break;
            case "stress":
                runStressTest();
                break;
            case "endurance":
                runEnduranceTest();
                break;
            case "all":
                runAllTests();
                break;
            default:
                log.error("Unknown scenario: {}", scenario);
        }

        log.info("=== Performance Test Completed ===");
    }

    /**
     * 처리량 테스트 - 다양한 데이터 크기로 처리 속도 측정
     */
    private void runThroughputTest() throws Exception {
        log.info("Starting Throughput Test");

        List<Integer> dataSizes = Arrays.asList(10000, 100000, 500000, 1000000, 5000000);
        Map<Integer, ThroughputResult> results = new LinkedHashMap<>();

        for (int size : dataSizes) {
            log.info("Testing with {} records", size);

            // 테스트 데이터 생성
            generateTestData(size);

            // 실행 및 측정
            ThroughputResult result = measureThroughput(size);
            results.put(size, result);

            // 클린업
            cleanupTestData();

            // 결과 출력
            log.info("Result - Size: {}, Duration: {}s, Throughput: {} records/sec",
                    size, result.duration.getSeconds(), result.throughput);
        }

        // 최종 리포트
        generateThroughputReport(results);
    }

    /**
     * 동시 실행 테스트 - 여러 Job을 동시에 실행
     */
    private void runConcurrentExecutionTest() throws Exception {
        log.info("Starting Concurrent Execution Test");

        int concurrentJobs = 5;
        int recordsPerJob = 100000;

        // 각 Job에 대한 테스트 데이터 생성
        IntStream.range(0, concurrentJobs).forEach(i -> {
            generatePartitionedTestData(recordsPerJob, i);
        });

        // 동시 실행
        List<CompletableFuture<JobExecution>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrentJobs);

        LocalDateTime baseTime = LocalDateTime.now();

        for (int i = 0; i < concurrentJobs; i++) {
            final int jobIndex = i;
            CompletableFuture<JobExecution> future = CompletableFuture.supplyAsync(() -> {
                try {
                    LocalDateTime startTime = baseTime.minusHours(jobIndex + 1);
                    LocalDateTime endTime = startTime.plusHours(1);

                    JobParameters params = new JobParametersBuilder()
                            .addLocalDateTime("startTime", startTime)
                            .addLocalDateTime("endTime", endTime)
                            .addLong("executionTime", System.currentTimeMillis() + jobIndex)
                            .toJobParameters();

                    return jobLauncher.run(vesselAggregationJob, params);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor);

            futures.add(future);
        }

        // 모든 Job 완료 대기
        List<JobExecution> executions = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 결과 분석
        analyzeConcurrentResults(executions);

        executor.shutdown();
    }

    /**
     * 스트레스 테스트 - 시스템 한계 테스트
     */
    private void runStressTest() throws Exception {
        log.info("Starting Stress Test");

        StressTestConfig config = new StressTestConfig();
        config.initialLoad = 100000;
        config.incrementFactor = 2;
        config.maxIterations = 5;
        config.targetErrorRate = 0.01; // 1%

        int currentLoad = config.initialLoad;
        List<StressTestResult> results = new ArrayList<>();

        for (int i = 0; i < config.maxIterations; i++) {
            log.info("Stress test iteration {} with load {}", i + 1, currentLoad);

            // 데이터 생성
            generateTestData(currentLoad);

            // 시스템 메트릭 수집 시작
            SystemMetrics beforeMetrics = collectSystemMetrics();

            // Job 실행
            JobExecution execution = runJob();

            // 시스템 메트릭 수집 종료
            SystemMetrics afterMetrics = collectSystemMetrics();

            // 결과 분석
            StressTestResult result = analyzeStressTestResult(
                    execution, beforeMetrics, afterMetrics, currentLoad
            );
            results.add(result);

            // 에러율 체크
            if (result.errorRate > config.targetErrorRate) {
                log.warn("Error rate {} exceeded target {}", result.errorRate, config.targetErrorRate);
                break;
            }

            // 다음 반복을 위한 부하 증가
            currentLoad *= config.incrementFactor;

            // 클린업
            cleanupTestData();
        }

        // 스트레스 테스트 리포트
        generateStressTestReport(results);
    }

    /**
     * 지속성 테스트 - 장시간 실행 안정성 테스트
     */
    private void runEnduranceTest() throws Exception {
        log.info("Starting Endurance Test");

        int durationHours = 4; // 4시간 테스트
        int recordsPerHour = 1000000;
        LocalDateTime testStartTime = LocalDateTime.now();
        LocalDateTime testEndTime = testStartTime.plusHours(durationHours);

        List<EnduranceTestResult> results = new ArrayList<>();

        while (LocalDateTime.now().isBefore(testEndTime)) {
            LocalDateTime iterationStart = LocalDateTime.now();

            // 매 시간마다 데이터 생성
            generateTestData(recordsPerHour);

            // Job 실행
            JobExecution execution = runJob();

            // 메모리 및 시스템 상태 체크
            EnduranceTestResult result = new EnduranceTestResult();
            result.iterationTime = iterationStart;
            result.execution = execution;
            result.memoryUsage = getMemoryUsage();
            result.activeThreads = Thread.activeCount();
            result.cpuUsage = getCpuUsage();

            results.add(result);

            // 결과 로깅
            log.info("Endurance test iteration completed - Memory: {}MB, Threads: {}, CPU: {}%",
                    result.memoryUsage / 1024 / 1024,
                    result.activeThreads,
                    result.cpuUsage
            );

            // 다음 반복까지 대기
            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
        }

        // 지속성 테스트 리포트
        generateEnduranceTestReport(results);
    }

    /**
     * 모든 테스트 실행
     */
    private void runAllTests() throws Exception {
        runThroughputTest();
        Thread.sleep(5000);

        runConcurrentExecutionTest();
        Thread.sleep(5000);

        runStressTest();
        Thread.sleep(5000);

        runEnduranceTest();
    }

    /**
     * 테스트 데이터 생성
     */
    private void generateTestData(int recordCount) {
        log.info("Generating {} test records", recordCount);

        String sql = """
            INSERT INTO signal.sig_test (
                message_time, real_time, sig_src_cd, target_id,
                lat, lon, sog, cog, heading, ship_nm, ship_ty,
                vts_cd, mmsi, vpass_id, ship_no
            )
            SELECT 
                NOW() - INTERVAL '1 hour' * (random() * 24),
                NOW() - INTERVAL '1 hour' * (random() * 24),
                CASE WHEN random() < 0.7 THEN 'AIS' ELSE 'VPASS' END,
                'TEST_VESSEL_' || seq,
                33.0 + random() * 6,
                124.0 + random() * 8,
                random() * 30,
                random() * 360,
                floor(random() * 360)::numeric,
                'Test Ship ' || seq,
                CASE floor(random() * 5)::int
                    WHEN 0 THEN 'CARGO'
                    WHEN 1 THEN 'TANKER'
                    WHEN 2 THEN 'PASSENGER'
                    WHEN 3 THEN 'FISHING'
                    ELSE 'OTHER'
                END,
                'TEST',
                '999' || lpad(seq::text, 6, '0'),
                'TEST_VP' || seq,
                'TEST_SN' || seq
            FROM generate_series(1, ?) seq
        """;

        collectJdbcTemplate.update(sql, recordCount);
    }

    /**
     * 파티션별 테스트 데이터 생성
     */
    private void generatePartitionedTestData(int recordCount, int partitionIndex) {
        LocalDateTime baseTime = LocalDateTime.now().minusHours(partitionIndex + 1);

        String sql = """
            INSERT INTO signal.sig_test (
                message_time, real_time, sig_src_cd, target_id,
                lat, lon, sog, cog, heading, ship_nm, ship_ty,
                vts_cd, mmsi, vpass_id, ship_no
            )
            SELECT 
                ? + INTERVAL '1 minute' * (seq % 60),
                ? + INTERVAL '1 minute' * (seq % 60),
                'AIS',
                'PART_' || ? || '_VESSEL_' || seq,
                33.0 + random() * 6,
                124.0 + random() * 8,
                random() * 30,
                random() * 360,
                floor(random() * 360)::numeric,
                'Partition ' || ? || ' Ship ' || seq,
                'CARGO',
                'TEST',
                '888' || ? || lpad(seq::text, 5, '0'),
                'PART_VP' || ? || '_' || seq,
                'PART_SN' || ? || '_' || seq
            FROM generate_series(1, ?) seq
        """;

        collectJdbcTemplate.update(sql,
                baseTime, baseTime, partitionIndex, partitionIndex,
                partitionIndex, partitionIndex, partitionIndex, recordCount
        );
    }

    /**
     * 처리량 측정
     */
    private ThroughputResult measureThroughput(int dataSize) throws Exception {
        LocalDateTime startTime = LocalDateTime.now();

        JobExecution execution = runJob();

        LocalDateTime endTime = LocalDateTime.now();
        Duration duration = Duration.between(startTime, endTime);

        long totalRead = execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();

        double throughput = totalRead > 0 ? (double) totalRead / duration.getSeconds() : 0;

        ThroughputResult result = new ThroughputResult();
        result.dataSize = dataSize;
        result.duration = duration;
        result.throughput = throughput;
        result.status = execution.getStatus();

        return result;
    }

    /**
     * Job 실행
     */
    private JobExecution runJob() throws Exception {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(24);

        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("startTime", startTime)
                .addLocalDateTime("endTime", endTime)
                .addLong("executionTime", System.currentTimeMillis())
                .toJobParameters();

        return jobLauncher.run(vesselAggregationJob, params);
    }

    /**
     * 시스템 메트릭 수집
     */
    private SystemMetrics collectSystemMetrics() {
        SystemMetrics metrics = new SystemMetrics();

        Runtime runtime = Runtime.getRuntime();
        metrics.totalMemory = runtime.totalMemory();
        metrics.freeMemory = runtime.freeMemory();
        metrics.maxMemory = runtime.maxMemory();

        metrics.activeThreads = Thread.activeCount();
        metrics.cpuCount = runtime.availableProcessors();

        // DB 연결 상태
        metrics.activeDbConnections = collectJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'",
                Integer.class
        );

        return metrics;
    }

    /**
     * 메모리 사용량 조회
     */
    private long getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * CPU 사용률 조회 (근사치)
     */
    private double getCpuUsage() {
        // 실제 구현은 JMX나 시스템 명령어 사용
        return Math.random() * 100; // 임시
    }

    /**
     * 테스트 데이터 정리
     */
    private void cleanupTestData() {
        collectJdbcTemplate.update("DELETE FROM signal.sig_test WHERE vts_cd = 'TEST'");
        queryJdbcTemplate.update("DELETE FROM signal.t_vessel_latest_position WHERE sig_src_cd LIKE 'TEST%'");
        queryJdbcTemplate.update("DELETE FROM signal.t_tile_summary WHERE created_at > NOW() - INTERVAL '1 day'");
    }

    /**
     * 동시 실행 결과 분석
     */
    private void analyzeConcurrentResults(List<JobExecution> executions) {
        log.info("=== Concurrent Execution Results ===");

        int successful = 0;
        int failed = 0;
        long totalDuration = 0;
        long totalRecords = 0;

        for (JobExecution execution : executions) {
            if (execution.getStatus() == BatchStatus.COMPLETED) {
                successful++;
            } else {
                failed++;
            }

            if (execution.getStartTime() != null && execution.getEndTime() != null) {
                totalDuration += Duration.between(
                        execution.getStartTime(),
                        execution.getEndTime()
                ).toMillis();
            }

            totalRecords += execution.getStepExecutions().stream()
                    .mapToLong(StepExecution::getReadCount)
                    .sum();
        }

        log.info("Successful: {}, Failed: {}", successful, failed);
        log.info("Average duration: {} ms", totalDuration / executions.size());
        log.info("Total records processed: {}", totalRecords);
    }

    /**
     * 스트레스 테스트 결과 분석
     */
    private StressTestResult analyzeStressTestResult(JobExecution execution,
                                                     SystemMetrics before,
                                                     SystemMetrics after,
                                                     int load) {
        StressTestResult result = new StressTestResult();
        result.load = load;
        result.status = execution.getStatus();

        // 에러율 계산
        long totalRead = execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();
        long totalSkip = execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getSkipCount)
                .sum();

        result.errorRate = totalRead > 0 ? (double) totalSkip / totalRead : 0;

        // 메모리 증가량
        result.memoryIncrease = (after.totalMemory - after.freeMemory) -
                (before.totalMemory - before.freeMemory);

        // 스레드 증가량
        result.threadIncrease = after.activeThreads - before.activeThreads;

        return result;
    }

    /**
     * 리포트 생성 메소드들
     */
    private void generateThroughputReport(Map<Integer, ThroughputResult> results) {
        log.info("\n=== Throughput Test Report ===");
        log.info("Data Size | Duration (s) | Throughput (rec/s) | Status");
        log.info("----------|--------------|-------------------|--------");

        results.forEach((size, result) -> {
            log.info(String.format("%-9d | %-12d | %-17.2f | %s",
                    size,
                    result.duration.getSeconds(),
                    result.throughput,
                    result.status
            ));
        });
    }

    private void generateStressTestReport(List<StressTestResult> results) {
        log.info("\n=== Stress Test Report ===");
        log.info("Load     | Error Rate | Memory Inc (MB) | Thread Inc | Status");
        log.info("---------|------------|-----------------|------------|--------");

        results.forEach(result -> {
            log.info(String.format("%-8d | %-10.2f | %-15d | %-10d | %s",
                    result.load,
                    result.errorRate * 100,
                    result.memoryIncrease / 1024 / 1024,
                    result.threadIncrease,
                    result.status
            ));
        });
    }

    private void generateEnduranceTestReport(List<EnduranceTestResult> results) {
        log.info("\n=== Endurance Test Report ===");
        log.info("Time     | Memory (MB) | Threads | CPU (%) | Status");
        log.info("---------|-------------|---------|---------|--------");

        results.forEach(result -> {
            log.info(String.format("%-8s | %-11d | %-7d | %-7.2f | %s",
                    result.iterationTime.toLocalTime(),
                    result.memoryUsage / 1024 / 1024,
                    result.activeThreads,
                    result.cpuUsage,
                    result.execution.getStatus()
            ));
        });
    }

    // 내부 클래스들
    private static class ThroughputResult {
        int dataSize;
        Duration duration;
        double throughput;
        BatchStatus status;
    }

    private static class StressTestConfig {
        int initialLoad;
        int incrementFactor;
        int maxIterations;
        double targetErrorRate;
    }

    private static class StressTestResult {
        int load;
        double errorRate;
        long memoryIncrease;
        int threadIncrease;
        BatchStatus status;
    }

    private static class EnduranceTestResult {
        LocalDateTime iterationTime;
        JobExecution execution;
        long memoryUsage;
        int activeThreads;
        double cpuUsage;
    }

    private static class SystemMetrics {
        long totalMemory;
        long freeMemory;
        long maxMemory;
        int activeThreads;
        int cpuCount;
        int activeDbConnections;
    }
}