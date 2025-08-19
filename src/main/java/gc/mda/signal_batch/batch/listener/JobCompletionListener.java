package gc.mda.signal_batch.batch.listener;

import gc.mda.signal_batch.global.util.BatchUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;


@Slf4j
@Component
@RequiredArgsConstructor
public class JobCompletionListener implements JobExecutionListener {

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    private final BatchUtils batchUtils;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("=== Starting Job: {} ===", jobExecution.getJobInstance().getJobName());
        log.info("Job Parameters: {}", jobExecution.getJobParameters());

        // 메모리 상태 로깅
        batchUtils.logMemoryUsage("Job Start");

        // 이전 실행 정리 (필요한 경우)
//        cleanupPreviousRun(jobExecution);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        log.info("=== Job Completed: {} ===", jobExecution.getJobInstance().getJobName());
        log.info("Status: {}", jobExecution.getStatus());

        // 실행 요약
        String summary = batchUtils.getJobExecutionSummary(jobExecution);
        log.info("Execution Summary:\n{}", summary);

        // 성능 메트릭 저장
        savePerformanceMetrics(jobExecution);

        // 메모리 상태 로깅
        batchUtils.logMemoryUsage("Job End");

        // 실패 시 알림
        if (jobExecution.getStatus().isUnsuccessful()) {
            sendFailureNotification(jobExecution);
        }
    }

    @SuppressWarnings("unused")
    private void cleanupPreviousRun(JobExecution jobExecution) {
        // 임시 테이블 정리 등
        try {
            queryJdbcTemplate.execute("TRUNCATE TABLE IF EXISTS temp_vessel_processing");
        } catch (Exception e) {
            log.warn("Failed to cleanup previous run: {}", e.getMessage());
        }
    }

    private void savePerformanceMetrics(JobExecution jobExecution) {
        try {
            LocalDateTime startTime = jobExecution.getStartTime();
            LocalDateTime endTime = jobExecution.getEndTime();

            // null 체크 추가
            if (startTime == null || endTime == null) {
                log.warn("Cannot save performance metrics - start or end time is null");
                return;
            }

            // LocalDateTime으로 직접 Duration 계산
            Duration duration = Duration.between(startTime, endTime);

            long totalRead = 0;
            long totalWrite = 0;

            for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
                totalRead += stepExecution.getReadCount();
                totalWrite += stepExecution.getWriteCount();
            }

            double throughput = batchUtils.calculateThroughput(totalRead, duration);

            String sql = """
                INSERT INTO signal.t_batch_performance_metrics (
                    job_name, execution_id, start_time, end_time,
                    duration_seconds, total_read, total_write,
                    throughput_per_sec, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

            queryJdbcTemplate.update(sql,
                    jobExecution.getJobInstance().getJobName(),
                    jobExecution.getId(),
                    startTime,
                    endTime,
                    duration.getSeconds(),
                    totalRead,
                    totalWrite,
                    throughput,
                    jobExecution.getStatus().toString(),
                    LocalDateTime.now()
            );

            log.info("Performance metrics saved - Duration: {} seconds, Throughput: {} records/sec",
                    duration.getSeconds(), throughput);

        } catch (Exception e) {
            log.error("Failed to save performance metrics", e);
        }
    }

    private void sendFailureNotification(JobExecution jobExecution) {
        // 실패 알림 로직 (이메일, Slack 등)
        log.error("Job {} failed with status: {}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getStatus()
        );

        if (jobExecution.getAllFailureExceptions() != null && !jobExecution.getAllFailureExceptions().isEmpty()) {
            jobExecution.getAllFailureExceptions().forEach(e ->
                    log.error("Failure reason: ", e)
            );
        }
    }
}