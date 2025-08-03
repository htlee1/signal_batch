package gc.mda.signal_batch.monitor;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetricsCollector implements JobExecutionListener, StepExecutionListener, ChunkListener {

    private final MeterRegistry meterRegistry;

    // 메트릭 캐시
    private final ConcurrentHashMap<String, LocalDateTime> startTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> recordCounters = new ConcurrentHashMap<>();

    // Job 메트릭
    @Override
    public void beforeJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String jobId = String.valueOf(jobExecution.getId());

        // Job 시작 카운터
        Counter.builder("batch.job.started")
                .tag("job.name", jobName)
                .tag("job.id", jobId)
                .description("Number of jobs started")
                .register(meterRegistry)
                .increment();

        // 활성 Job 게이지
        Gauge.builder("batch.job.active", () -> getActiveJobCount())
                .tag("job.name", jobName)
                .description("Number of active jobs")
                .register(meterRegistry);

        startTimes.put("job_" + jobId, LocalDateTime.now());

        log.info("Job {} started - ID: {}", jobName, jobId);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String jobId = String.valueOf(jobExecution.getId());
        BatchStatus status = jobExecution.getStatus();

        // Job 완료 카운터
        Counter.builder("batch.job.completed")
                .tag("job.name", jobName)
                .tag("job.id", jobId)
                .tag("status", status.toString())
                .description("Number of jobs completed")
                .register(meterRegistry)
                .increment();

        // Job 실행 시간
        LocalDateTime startTime = startTimes.remove("job_" + jobId);
        if (startTime != null) {
            long duration = Duration.between(startTime, LocalDateTime.now()).toMillis();

            Timer.builder("batch.job.duration")
                    .tag("job.name", jobName)
                    .tag("status", status.toString())
                    .description("Job execution duration")
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
        }

        // 전체 처리 레코드 수
        long totalRead = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();

        long totalWrite = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();

        long totalSkip = jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getSkipCount)
                .sum();

        // 처리량 메트릭
        Counter.builder("batch.job.records.read")
                .tag("job.name", jobName)
                .register(meterRegistry)
                .increment(totalRead);

        Counter.builder("batch.job.records.write")
                .tag("job.name", jobName)
                .register(meterRegistry)
                .increment(totalWrite);

        Counter.builder("batch.job.records.skip")
                .tag("job.name", jobName)
                .register(meterRegistry)
                .increment(totalSkip);

        // 에러율
        if (totalRead > 0) {
            double errorRate = (double) totalSkip / totalRead;
            Gauge.builder("batch.job.error.rate", () -> errorRate)
                    .tag("job.name", jobName)
                    .register(meterRegistry);
        }

        log.info("Job {} completed - Status: {}, Read: {}, Write: {}, Skip: {}",
                jobName, status, totalRead, totalWrite, totalSkip);
    }

    // Step 메트릭
    @Override
    public void beforeStep(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();
        String executionId = String.valueOf(stepExecution.getId());

        Counter.builder("batch.step.started")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment();

        startTimes.put("step_" + executionId, LocalDateTime.now());
        recordCounters.put("step_" + executionId + "_chunks", new AtomicLong(0));
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();
        String executionId = String.valueOf(stepExecution.getId());

        // Step 실행 시간
        LocalDateTime startTime = startTimes.remove("step_" + executionId);
        if (startTime != null) {
            long duration = Duration.between(startTime, LocalDateTime.now()).toMillis();

            Timer.builder("batch.step.duration")
                    .tag("step.name", stepName)
                    .tag("status", stepExecution.getStatus().toString())
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);

            // 처리 속도 (records/sec)
            if (duration > 0 && stepExecution.getReadCount() > 0) {
                double throughput = (stepExecution.getReadCount() * 1000.0) / duration;

                Gauge.builder("batch.step.throughput", () -> throughput)
                        .tag("step.name", stepName)
                        .baseUnit("records/second")
                        .register(meterRegistry);
            }
        }

        // Step 메트릭
        recordStepMetrics(stepExecution);

        // Chunk 수
        AtomicLong chunkCount = recordCounters.remove("step_" + executionId + "_chunks");
        if (chunkCount != null) {
            Gauge.builder("batch.step.chunks", chunkCount::get)
                    .tag("step.name", stepName)
                    .register(meterRegistry);
        }

        return stepExecution.getExitStatus();
    }

    // Chunk 메트릭
    @Override
    public void beforeChunk(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();
        String executionId = String.valueOf(context.getStepContext().getStepExecution().getId());

        AtomicLong chunkCounter = recordCounters.get("step_" + executionId + "_chunks");
        if (chunkCounter != null) {
            chunkCounter.incrementAndGet();
        }

        startTimes.put("chunk_" + executionId + "_" + System.nanoTime(), LocalDateTime.now());
    }

    @Override
    public void afterChunk(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();

        // Chunk 처리 시간
        String chunkKey = startTimes.keySet().stream()
                .filter(k -> k.startsWith("chunk_" + context.getStepContext().getStepExecution().getId()))
                .findFirst()
                .orElse(null);

        if (chunkKey != null) {
            LocalDateTime startTime = startTimes.remove(chunkKey);
            if (startTime != null) {
                long duration = Duration.between(startTime, LocalDateTime.now()).toMillis();

                Timer.builder("batch.chunk.duration")
                        .tag("step.name", stepName)
                        .register(meterRegistry)
                        .record(duration, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();

        Counter.builder("batch.chunk.errors")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment();
    }

    // 커스텀 메트릭 메소드
    public void recordDatabaseOperation(String operation, String table, long duration, boolean success) {
        Timer.builder("batch.database.operation")
                .tag("operation", operation)
                .tag("table", table)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(duration, TimeUnit.MILLISECONDS);
    }

    public void recordPartitionMetrics(String table, String partition, long recordCount, long sizeBytes) {
        Gauge.builder("batch.partition.records", () -> recordCount)
                .tag("table", table)
                .tag("partition", partition)
                .register(meterRegistry);

        Gauge.builder("batch.partition.size", () -> sizeBytes)
                .tag("table", table)
                .tag("partition", partition)
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    public void recordMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        Gauge.builder("batch.memory.used", () -> usedMemory)
                .baseUnit("bytes")
                .register(meterRegistry);

        Gauge.builder("batch.memory.max", () -> maxMemory)
                .baseUnit("bytes")
                .register(meterRegistry);

        Gauge.builder("batch.memory.usage.ratio", () -> (double) usedMemory / maxMemory)
                .register(meterRegistry);
    }

    // 헬퍼 메소드
    private void recordStepMetrics(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();

        // 읽기/쓰기/스킵 카운터
        Counter.builder("batch.step.records.read")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment(stepExecution.getReadCount());

        Counter.builder("batch.step.records.write")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment(stepExecution.getWriteCount());

        Counter.builder("batch.step.records.skip")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment(stepExecution.getSkipCount());

        // 커밋/롤백 카운터
        Counter.builder("batch.step.commits")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment(stepExecution.getCommitCount());

        Counter.builder("batch.step.rollbacks")
                .tag("step.name", stepName)
                .register(meterRegistry)
                .increment(stepExecution.getRollbackCount());
    }

    private long getActiveJobCount() {
        // 실제 구현은 JobExplorer를 사용
        return startTimes.keySet().stream()
                .filter(k -> k.startsWith("job_"))
                .count();
    }
    
    // 캐시 히트 기록
    public void recordCacheHit() {
        Counter.builder("batch.cache.hits")
                .description("Number of cache hits")
                .register(meterRegistry)
                .increment();
    }
    
    // 캐시 미스 기록
    public void recordCacheMiss() {
        Counter.builder("batch.cache.misses")
                .description("Number of cache misses")
                .register(meterRegistry)
                .increment();
    }
    
    // 메모리 최적화 기록
    public void recordMemoryOptimization(long freedMemory) {
        Counter.builder("batch.memory.optimizations")
                .description("Number of memory optimizations performed")
                .register(meterRegistry)
                .increment();
        
        Gauge.builder("batch.memory.freed", () -> freedMemory)
                .description("Memory freed by optimization")
                .baseUnit("bytes")
                .register(meterRegistry);
        
        log.info("Memory optimization freed {} MB", freedMemory / 1024 / 1024);
    }
}