package gc.mda.signal_batch.batch.listener;

import gc.mda.signal_batch.monitoring.health.BatchMetricsCollector;
import gc.mda.signal_batch.monitoring.performance.BatchProcessingOptimizer;
import gc.mda.signal_batch.monitoring.performance.PerformanceOptimizationManager;
import gc.mda.signal_batch.monitoring.performance.QueryPerformanceOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.annotation.AfterChunk;
import org.springframework.batch.core.annotation.BeforeChunk;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 성능 최적화 리스너
 * 배치 작업 실행 중 성능 최적화를 자동으로 수행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceOptimizationListener implements JobExecutionListener, StepExecutionListener, ChunkListener {

    private final PerformanceOptimizationManager optimizationManager;
    private final BatchProcessingOptimizer batchOptimizer;
    private final QueryPerformanceOptimizer queryOptimizer;
    @SuppressWarnings("unused")
    private final BatchMetricsCollector metricsCollector;

    private final ConcurrentHashMap<String, LocalDateTime> executionStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> currentChunkSizes = new ConcurrentHashMap<>();

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        log.info("Starting performance optimization for job: {}", jobName);
        
        // 메모리 상태 확인 및 최적화
        if (optimizationManager.getStatus().getMemoryUsage().getPercentage() > 70) {
            log.warn("High memory usage detected before job start, performing optimization");
            optimizationManager.performMemoryOptimization();
        }
        
        executionStartTimes.put(jobName, LocalDateTime.now());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        LocalDateTime startTime = executionStartTimes.remove(jobName);
        
        if (startTime != null) {
            Duration duration = Duration.between(startTime, LocalDateTime.now());
            log.info("Job {} completed in {} seconds", jobName, duration.getSeconds());
            
            // 성능 리포트 생성
            if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                String report = optimizationManager.generatePerformanceReport();
                log.info("Performance Report for job {}: {}", jobName, report);
            }
        }
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();
        executionStartTimes.put(stepName, LocalDateTime.now());
        
        // 초기 청크 크기 설정
        Long chunkSizeParam = stepExecution.getJobParameters().getLong("chunkSize", 5000L);
        int chunkSize = chunkSizeParam != null ? chunkSizeParam.intValue() : 5000;
        currentChunkSizes.put(stepName, chunkSize);
        
        log.debug("Starting step {} with chunk size: {}", stepName, chunkSize);
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();
        LocalDateTime startTime = executionStartTimes.remove(stepName);
        
        if (startTime != null) {
            Duration duration = Duration.between(startTime, LocalDateTime.now());
            Long readCountParam = stepExecution.getReadCount();
            int readCount = readCountParam != null ? readCountParam.intValue() : 0;
            
            // 동적 청크 크기 최적화
            Integer currentChunkSize = currentChunkSizes.get(stepName);
            if (currentChunkSize != null && readCount > 0) {
                int optimalChunkSize = batchOptimizer.calculateOptimalChunkSize(
                    stepName, 
                    currentChunkSize, 
                    duration.toMillis(), 
                    readCount
                );
                
                if (optimalChunkSize != currentChunkSize) {
                    log.info("Recommended chunk size for step {} changed from {} to {}", 
                            stepName, currentChunkSize, optimalChunkSize);
                    // 다음 실행을 위해 저장
                    stepExecution.getExecutionContext().putInt("optimalChunkSize", optimalChunkSize);
                }
            }
            
            // 쿼리 성능 기록
            queryOptimizer.recordQueryPerformance(stepName, duration.toMillis());
        }
        
        return stepExecution.getExitStatus();
    }

    @BeforeChunk
    public void beforeChunk(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();
        context.setAttribute("chunkStartTime", System.currentTimeMillis());
        
        // 캐시 히트율 모니터링
        if (optimizationManager.getStatus().getCacheHitRate() < 50) {
            log.warn("Low cache hit rate detected in step: {}", stepName);
        }
    }

    @AfterChunk
    public void afterChunk(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();
        Long startTime = (Long) context.getAttribute("chunkStartTime");
        
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            
            // 청크 처리 시간이 너무 길면 경고
            if (duration > 10000) { // 10초 이상
                log.warn("Chunk processing in step {} took {} ms", stepName, duration);
                
                // 메모리 압박 확인
                if (optimizationManager.getStatus().getMemoryUsage().getPercentage() > 85) {
                    log.error("Critical memory usage during chunk processing, triggering optimization");
                    optimizationManager.performMemoryOptimization();
                }
            }
        }
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();
        log.error("Chunk error in step: {}", stepName);
        
        // 에러 발생 시 청크 크기 자동 감소
        Integer currentChunkSize = currentChunkSizes.get(stepName);
        if (currentChunkSize != null && currentChunkSize > 1000) {
            int reducedSize = (int) (currentChunkSize * 0.5);
            currentChunkSizes.put(stepName, reducedSize);
            log.warn("Reducing chunk size for step {} to {} due to error", stepName, reducedSize);
        }
    }
}