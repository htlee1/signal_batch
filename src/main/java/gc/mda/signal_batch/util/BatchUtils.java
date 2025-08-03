package gc.mda.signal_batch.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

@Slf4j
@Component
public class BatchUtils {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 기본 Job 파라미터 생성
     */
    public JobParameters createJobParameters(LocalDateTime startTime, LocalDateTime endTime) {
        return new JobParametersBuilder()
                .addLocalDateTime("startTime", startTime)
                .addLocalDateTime("endTime", endTime)
                .addLong("executionTime", System.currentTimeMillis())
                .addLong("tileLevel", 1L)  // 기본값 1 (소해구도)
                .addLong("timeBucketMinutes", 5L)  // 5분 단위로 변경 (배치 주기와 일치)
                .toJobParameters();
    }

    /**
     * 일별 처리용 Job 파라미터 생성
     */
    public JobParameters createDailyJobParameters(LocalDate processingDate) {
        return new JobParametersBuilder()
                .addLocalDate("processingDate", processingDate)
                .addLocalDateTime("startTime", processingDate.atStartOfDay())
                .addLocalDateTime("endTime", processingDate.plusDays(1).atStartOfDay())
                .addLong("executionTime", System.currentTimeMillis())
                .toJobParameters();
    }

    /**
     * Job 실행 결과 요약
     */
    public String getJobExecutionSummary(JobExecution jobExecution) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Job: %s, Status: %s\n",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getStatus()
        ));

        // 방법 1: ZoneOffset 사용
        LocalDateTime startTime = jobExecution.getStartTime();
        LocalDateTime endTime = jobExecution.getEndTime();

        if (startTime != null && endTime != null) {
            // LocalDateTime을 Instant로 변환 시 시스템 기본 시간대 사용
            Instant startInstant = startTime.atZone(ZoneId.systemDefault()).toInstant();
            Instant endInstant = endTime.atZone(ZoneId.systemDefault()).toInstant();

            Duration duration = Duration.between(startInstant, endInstant);

            summary.append(String.format("Duration: %d minutes %d seconds\n",
                    duration.toMinutes(), duration.getSeconds() % 60
            ));
        } else if (startTime != null) {
            // endTime이 null인 경우 (실행 중)
            Instant startInstant = startTime.atZone(ZoneId.systemDefault()).toInstant();
            Duration duration = Duration.between(startInstant, Instant.now());

            summary.append(String.format("Running for: %d minutes %d seconds\n",
                    duration.toMinutes(), duration.getSeconds() % 60
            ));
        }

        // 또는 방법 2: LocalDateTime 직접 사용 (더 간단함)
        /*
        LocalDateTime startTime = jobExecution.getStartTime();
        LocalDateTime endTime = jobExecution.getEndTime() != null ?
            jobExecution.getEndTime() : LocalDateTime.now();

        if (startTime != null) {
            Duration duration = Duration.between(startTime, endTime);
            summary.append(String.format("Duration: %d minutes %d seconds\n",
                duration.toMinutes(), duration.getSeconds() % 60
            ));
        }
        */

        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        for (StepExecution stepExecution : stepExecutions) {
            summary.append(String.format("  Step: %s, Status: %s, Read: %d, Write: %d, Skip: %d\n",
                    stepExecution.getStepName(),
                    stepExecution.getStatus(),
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getSkipCount()
            ));
        }

        return summary.toString();
    }

    /**
     * 파티션 테이블명 생성
     */
    public String generatePartitionName(String baseTableName, LocalDate date) {
        return baseTableName + "_" + date.format(DateTimeFormatter.ofPattern("yyMMdd"));
    }

    /**
     * 처리 속도 계산 (records/sec)
     */
    public double calculateThroughput(long recordCount, Duration duration) {
        if (duration.getSeconds() == 0) {
            return 0;
        }
        return (double) recordCount / duration.getSeconds();
    }

    /**
     * 메모리 사용량 로깅
     */
    public void logMemoryUsage(String context) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        log.info("{} - Memory usage: Used: {}MB, Free: {}MB, Total: {}MB, Max: {}MB",
                context, usedMemory, freeMemory, totalMemory, maxMemory);
    }

    /**
     * 재시도 가능 여부 판단
     */
    public boolean isRetryableException(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        return message.contains("Connection") ||
                message.contains("Timeout") ||
                message.contains("Lock") ||
                message.contains("Deadlock");
    }
}