package gc.mda.signal_batch.monitoring.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleMetricsLogger implements JobExecutionListener {

    /**
     * 1분마다 시스템 메트릭 로그
     */
    @Scheduled(fixedDelay = 60000)
    public void logSystemMetrics() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        
        log.info("=== SYSTEM METRICS === Memory: {}MB / {}MB ({}%), Active Threads: {}", 
            usedMemory, maxMemory, 
            (usedMemory * 100) / maxMemory,
            Thread.activeCount());
    }
    
    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("=== JOB STARTED === Job: {}, ID: {}", 
            jobExecution.getJobInstance().getJobName(),
            jobExecution.getId());
    }
    
    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String status = jobExecution.getStatus().toString();
        
        long duration = Duration.between(
            jobExecution.getStartTime().toInstant(ZoneOffset.UTC),
            jobExecution.getEndTime().toInstant(ZoneOffset.UTC)
        ).getSeconds();
        
        long totalRead = jobExecution.getStepExecutions().stream()
            .mapToLong(se -> se.getReadCount())
            .sum();
            
        long totalWrite = jobExecution.getStepExecutions().stream()
            .mapToLong(se -> se.getWriteCount())
            .sum();
            
        double throughput = duration > 0 ? (double) totalWrite / duration : 0;
        
        log.info("=== JOB COMPLETED === Job: {}, Status: {}, Duration: {}s, Read: {}, Write: {}, Throughput: {} rec/s", 
            jobName, status, duration, totalRead, totalWrite, throughput);
            
        // Step별 상세 정보
        jobExecution.getStepExecutions().forEach(step -> {
            log.info("  - Step: {}, Read: {}, Write: {}, Skip: {}, Commits: {}", 
                step.getStepName(),
                step.getReadCount(),
                step.getWriteCount(),
                step.getSkipCount(),
                step.getCommitCount());
        });
    }
}