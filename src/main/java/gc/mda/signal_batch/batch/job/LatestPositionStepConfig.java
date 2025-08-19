package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import gc.mda.signal_batch.domain.vessel.model.VesselLatestPosition;
import gc.mda.signal_batch.batch.processor.LatestPositionProcessor;
import gc.mda.signal_batch.batch.reader.InMemoryVesselDataReader;
import gc.mda.signal_batch.batch.reader.PartitionedReader;
import gc.mda.signal_batch.batch.reader.VesselDataReader;
import gc.mda.signal_batch.batch.writer.UpsertWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LatestPositionStepConfig {

    private final JobRepository jobRepository;

    @Qualifier("queryTransactionManager")
    private final PlatformTransactionManager queryTransactionManager;

    private final LatestPositionProcessor latestPositionProcessor;
    private final UpsertWriter upsertWriter;
    private final PartitionedReader partitionedReader;
    private final ApplicationContext applicationContext;

    @Qualifier("batchTaskExecutor")
    private final TaskExecutor batchTaskExecutor;

    @Qualifier("partitionTaskExecutor")
    private final TaskExecutor partitionTaskExecutor;

    @Bean
    public Step updateLatestPositionStep() {
        // InMemoryVesselDataReader를 ApplicationContext에서 가져옴
        InMemoryVesselDataReader inMemoryReader = applicationContext.getBean(InMemoryVesselDataReader.class);
        
        return new StepBuilder("updateLatestPositionStep", jobRepository)
                .<VesselData, VesselLatestPosition>chunk(10000, queryTransactionManager)
                .reader(inMemoryReader)  // 메모리 기반 Reader 사용
                .processor(latestPositionProcessor.processor())
                .writer(upsertWriter.latestPositionWriter())
                .faultTolerant()
                .retryLimit(3)
                .retry(org.springframework.dao.CannotAcquireLockException.class)
                .skipLimit(1000)
                .skip(org.springframework.dao.EmptyResultDataAccessException.class)
                .skip(Exception.class)
                .build();
    }

    // 메모리 기반 Reader 사용으로 제거
    // @Bean
    // @StepScope
    // public ItemReader<VesselData> defaultVesselDataReader() { ... }

    @Bean
    public Step partitionedLatestPositionStep() {
        return new StepBuilder("partitionedLatestPositionStep", jobRepository)
                .partitioner("latestPositionPartitioner", dayPartitioner(null))
                .partitionHandler(latestPositionPartitionHandler())
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler latestPositionPartitionHandler() {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(latestPositionSlaveStep());
        handler.setGridSize(24);
        return handler;
    }

    @Bean
    public Step latestPositionSlaveStep() {
        return new StepBuilder("latestPositionSlaveStep", jobRepository)
                .<VesselData, VesselLatestPosition>chunk(3000, queryTransactionManager)
                .reader(slaveVesselDataReader(null, null, null))
                .processor(slaveLatestPositionProcessor())
                .writer(upsertWriter.latestPositionWriter())
                .faultTolerant()
                .retryPolicy(retryPolicy())
                .backOffPolicy(exponentialBackOffPolicy())
                .skipLimit(50)
                .skip(Exception.class)
                .noRollback(org.springframework.dao.DuplicateKeyException.class)
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<VesselData> slaveVesselDataReader(
            @Value("#{stepExecutionContext['startTime']}") String startTime,
            @Value("#{stepExecutionContext['endTime']}") String endTime,
            @Value("#{stepExecutionContext['partition']}") String partition) {

        // ApplicationContext에서 VesselDataReader를 가져와서 사용
        VesselDataReader reader = applicationContext.getBean(VesselDataReader.class);

        return reader.vesselLatestPositionReader(
                LocalDateTime.parse(startTime),
                LocalDateTime.parse(endTime),
                partition
        );
    }

    @Bean
    @StepScope
    public ItemProcessor<VesselData, VesselLatestPosition> slaveLatestPositionProcessor() {
        return latestPositionProcessor.processor();
    }

    @Bean
    @StepScope
    public org.springframework.batch.core.partition.support.Partitioner dayPartitioner(
            @Value("#{jobParameters['processingDate']}") String processingDateStr) {
        LocalDate processingDate = processingDateStr != null ? LocalDate.parse(processingDateStr) : null;
        return partitionedReader.dayPartitioner(processingDate);
    }

    @Bean
    public RetryPolicy retryPolicy() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(org.springframework.dao.CannotAcquireLockException.class, true);
        retryableExceptions.put(org.springframework.dao.DataAccessException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions);
        return retryPolicy;
    }

    @Bean
    public BackOffPolicy exponentialBackOffPolicy() {
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000); // 1초
        backOffPolicy.setMaxInterval(10000); // 최대 10초
        backOffPolicy.setMultiplier(2.0); // 2배씩 증가
        return backOffPolicy;
    }
}