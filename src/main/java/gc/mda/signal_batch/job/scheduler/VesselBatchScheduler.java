package gc.mda.signal_batch.job.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class VesselBatchScheduler {

    @Autowired
    @Qualifier("asyncJobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("vesselAggregationJob")
    private Job vesselAggregationJob;

    @Autowired
    @Qualifier("vesselTrackAggregationJob")
    private Job vesselTrackAggregationJob;

    @Autowired(required = false)
    @Qualifier("hourlyAggregationJob")
    private Job hourlyAggregationJob;

    @Autowired(required = false)
    @Qualifier("dailyAggregationJob")
    private Job dailyAggregationJob;

    @Value("${vessel.batch.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${vessel.batch.scheduler.incremental.delay-minutes:2}")
    private int incrementalDelayMinutes;

    @Value("${vessel.batch.abnormal-detection.enabled:true}")
    private boolean abnormalDetectionEnabled;
    /**
     * 5분 단위 증분 처리 (3분 지연으로 데이터 수집 대기)
     * 매 5분마다 실행 (0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55분)
     */
    @Scheduled(cron = "0 3,8,13,18,23,28,33,38,43,48,53,58 * * * *")
    public void runIncrementalAggregation() {
        if (!schedulerEnabled) {
            log.debug("Scheduler is disabled");
            return;
        }

        try {
            // 3분 전 데이터를 처리 (데이터 수집 지연 고려)
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime endTime = now.minusMinutes(incrementalDelayMinutes);
            LocalDateTime startTime = endTime.minusMinutes(5);

            log.info("Starting incremental aggregation for period: {} to {}", startTime, endTime);

            JobParameters params = new JobParametersBuilder()
                    .addString("startTime", startTime.withNano(0).toString())
                    .addString("endTime", endTime.withNano(0).toString())
                    .addLong("executionTime", System.currentTimeMillis())
                    .addString("jobType", "INCREMENTAL")
                    .addString("timeBucketMinutes", "5")  // 5분 단위 집계
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(vesselAggregationJob, params);

            log.info("Incremental aggregation started with execution ID: {}", execution.getId());

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("Previous incremental job is still running, skipping this execution");
        } catch (Exception e) {
            log.error("Failed to start incremental aggregation", e);
        }
    }
//
    /**
     * 5분 단위 궤적 집계 처리 (4분 지연으로 위치 집계 이후 실행)
     * 매 5분마다 실행 (4, 9, 14, 19, 24, 29, 34, 39, 44, 49, 54, 59분)
     */
    @Scheduled(cron = "0 4,9,14,19,24,29,34,39,44,49,54,59 * * * *")
    public void runTrackAggregation() {
        if (!schedulerEnabled) {
            log.debug("Scheduler is disabled");
            return;
        }

        try {
            // 4분 전 데이터를 처리 (위치 집계 완료 후)
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime endTime = now.minusMinutes(incrementalDelayMinutes + 1); // 3+1=4분 지연
            LocalDateTime startTime = endTime.minusMinutes(5);

            // 5분 버킷 계산
            LocalDateTime timeBucket = startTime
                    .withSecond(0)
                    .withNano(0)
                    .minusMinutes(startTime.getMinute() % 5);

            log.info("Starting track aggregation for period: {} to {} (bucket: {})",
                    startTime, endTime, timeBucket);

            // Timestamp 형식에 맞게 포매팅
            DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            JobParameters params = new JobParametersBuilder()
                    .addString("startTime", startTime.withNano(0).toString())
                    .addString("endTime", endTime.withNano(0).toString())
                    .addString("timeBucket", timeBucket.format(timestampFormatter))
                    .addLong("executionTime", System.currentTimeMillis())
                    .addLong("nanoTime", System.nanoTime())  // 더 정밀한 고유값
                    .addString("jobType", "TRACK_INCREMENTAL")
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(vesselTrackAggregationJob, params);

            log.info("Track aggregation started with execution ID: {}", execution.getId());

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("Previous track job is still running, skipping this execution");
        } catch (Exception e) {
            log.error("Failed to start track aggregation", e);
        }
    }

    /**
     * 향상된 1시간 집계 스케줄 - 매시 10분
     * 비정상 궤적 검출 기능 포함
     */
    @Scheduled(cron = "0 10 * * * *")
    public void runHourlyAggregation() {
        if (!schedulerEnabled || hourlyAggregationJob == null) {
            log.debug("Hourly aggregation job is not available");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = now.minusHours(1).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime endTime = startTime.plusHours(1);

            JobParameters params = new JobParametersBuilder()
                    .addString("startTime", startTime.toString())
                    .addString("endTime", endTime.toString())
                    .addString("timeBucket", "hourly")
                    .addString("executionTime", now.toString())
                    .addString("enableAbnormalDetection", String.valueOf(abnormalDetectionEnabled))
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(hourlyAggregationJob, params);
            log.info("Started enhanced hourly aggregation job: {} for period {} to {} (abnormal detection: {})",
                    execution.getId(), startTime, endTime, abnormalDetectionEnabled);
        } catch (Exception e) {
            log.error("Failed to start enhanced hourly aggregation job", e);
        }
    }

    /**
     * 향상된 1일 집계 스케줄 - 매일 01:00
     * 비정상 궤적 검출 기능 포함
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void runDailyAggregation() {
        if (!schedulerEnabled || dailyAggregationJob == null) {
            log.debug("Enhanced daily aggregation job is not available");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = now.minusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime endTime = startTime.plusDays(1);

            JobParameters params = new JobParametersBuilder()
                    .addString("startTime", startTime.toString())
                    .addString("endTime", endTime.toString())
                    .addString("timeBucket", "daily")
                    .addString("executionTime", now.toString())
                    .addString("enableAbnormalDetection", String.valueOf(abnormalDetectionEnabled))
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(dailyAggregationJob, params);
            log.info("Started enhanced daily aggregation job: {} for date {} (abnormal detection: {})",
                    execution.getId(), startTime.toLocalDate(), abnormalDetectionEnabled);
        } catch (Exception e) {
            log.error("Failed to start enhanced daily aggregation job", e);
        }
    }

    /**
     * 비정상 궤적 통계 집계 (매일 02:00)
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void updateAbnormalTrackStatistics() {
        if (!schedulerEnabled || !abnormalDetectionEnabled) {
            return;
        }

        try {
            log.info("Updating abnormal track statistics...");
            // TODO: 통계 업데이트 로직 구현
        } catch (Exception e) {
            log.error("Failed to update abnormal track statistics", e);
        }
    }
    /**
     * 데이터 지연 모니터링 및 캐치업 작업 (10분마다)
     */
    @Scheduled(fixedDelay = 600000, initialDelay = 60000)
    public void monitorAndCatchUp() {
        if (!schedulerEnabled) {
            return;
        }

        try {
            // TODO: 데이터 지연 확인 로직 구현
            // 지연이 10분 이상이면 캐치업 작업 실행

        } catch (Exception e) {
            log.error("Failed to monitor data delay", e);
        }
    }
}