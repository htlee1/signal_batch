package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import gc.mda.signal_batch.batch.processor.AccumulatingAreaProcessor;
import gc.mda.signal_batch.batch.processor.AreaStatisticsProcessor;
import gc.mda.signal_batch.batch.processor.AreaStatisticsProcessor.AreaStatistics;
import gc.mda.signal_batch.batch.reader.InMemoryVesselDataReader;
import gc.mda.signal_batch.batch.reader.PartitionedReader;
import gc.mda.signal_batch.batch.reader.VesselDataReader;
import gc.mda.signal_batch.batch.writer.UpsertWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Configuration
@RequiredArgsConstructor
public class AreaStatisticsStepConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager queryTransactionManager;
    private final VesselDataReader vesselDataReader;

    private final AccumulatingAreaProcessor accumulatingAreaProcessor;
    private final AreaStatisticsProcessor areaStatisticsProcessor;
    private final UpsertWriter upsertWriter;
    private final PartitionedReader partitionedReader;
    private final ApplicationContext applicationContext;
    
    @Value("${vessel.batch.area-statistics.chunk-size:1000}")
    private int areaChunkSize;
    
    @Value("${vessel.batch.area-statistics.batch-size:500}")
    private int areaBatchSize;

    @Qualifier("batchTaskExecutor")
    private final TaskExecutor batchTaskExecutor;

    @Qualifier("partitionTaskExecutor")
    private final TaskExecutor partitionTaskExecutor;

    @Bean
    public Step aggregateAreaStatisticsStep() {
        // InMemoryVesselDataReader를 ApplicationContext에서 가져옴
        InMemoryVesselDataReader inMemoryReader = applicationContext.getBean(InMemoryVesselDataReader.class);
        
        return new StepBuilder("aggregateAreaStatisticsStep", jobRepository)
                .<VesselData, AreaStatistics>chunk(areaChunkSize, queryTransactionManager)
                .reader(inMemoryReader)  // 메모리 기반 Reader 사용
                .processor(accumulatingAreaProcessor)
                .writer(items -> {}) // 빈 writer, 실제 저장은 listener에서
                .listener(areaStatisticsStepListener())
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .build();
    }

    @Bean
    public Step partitionedAreaStatisticsStep() {
        return new StepBuilder("partitionedAreaStatisticsStep", jobRepository)
                .partitioner("areaStatisticsPartitioner", partitionedReader.dayPartitioner(null))
                .partitionHandler(areaStatisticsPartitionHandler())
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler areaStatisticsPartitionHandler() {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(areaStatisticsSlaveStep());
        handler.setGridSize(24);
        return handler;
    }

    @Bean
    public Step areaStatisticsSlaveStep() {
        return new StepBuilder("areaStatisticsSlaveStep", jobRepository)
                .<List<VesselData>, List<AreaStatistics>>chunk(50, queryTransactionManager)
                .reader(slaveAreaBatchVesselDataReader(null, null, null))
                .processor(areaStatisticsProcessor.batchProcessor())
                .writer(upsertWriter.areaStatisticsWriter())
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<VesselData> areaVesselDataReader(
            @Value("#{jobParameters['startTime']}") String startTimeStr,
            @Value("#{jobParameters['endTime']}") String endTimeStr) {
        return new ItemReader<VesselData>() {
            private ItemReader<VesselData> delegate;
            private boolean initialized = false;

            @Override
            public VesselData read() throws Exception {
                if (!initialized) {
                    LocalDateTime startTime = startTimeStr != null ? LocalDateTime.parse(startTimeStr) : null;
                    LocalDateTime endTime = endTimeStr != null ? LocalDateTime.parse(endTimeStr) : null;
                    
                    // 기존 reader close
                    if (delegate != null) {
                        try {
                            ((org.springframework.batch.item.ItemStream) delegate).close();
                        } catch (Exception e) {
                            log.debug("Failed to close previous reader: {}", e.getMessage());
                        }
                    }
                    
                    // 최신 위치만 사용
                    delegate = vesselDataReader.vesselLatestPositionReader(startTime, endTime, null);
                    ((org.springframework.batch.item.ItemStream) delegate).open(
                            org.springframework.batch.core.scope.context.StepSynchronizationManager
                                    .getContext().getStepExecution().getExecutionContext());
                    initialized = true;
                }
                
                VesselData data = delegate.read();
                
                // Reader 종료 시 close
                if (data == null && delegate != null) {
                    try {
                        ((org.springframework.batch.item.ItemStream) delegate).close();
                        delegate = null;
                        initialized = false;
                    } catch (Exception e) {
                        log.debug("Failed to close reader on completion: {}", e.getMessage());
                    }
                }
                
                return data;
            }
        };
    }

    @Bean
    @StepScope
    public ItemReader<List<VesselData>> slaveAreaBatchVesselDataReader(
            @Value("#{stepExecutionContext['startTime']}") String startTime,
            @Value("#{stepExecutionContext['endTime']}") String endTime,
            @Value("#{stepExecutionContext['partition']}") String partition) {

        return new ItemReader<List<VesselData>>() {
            private ItemReader<VesselData> delegate = vesselDataReader.vesselDataPagingReader(
                    startTime != null ? LocalDateTime.parse(startTime) : null,
                    endTime != null ? LocalDateTime.parse(endTime) : null,
                    partition
            );

            @Override
            public List<VesselData> read() throws Exception {
                List<VesselData> batch = new java.util.ArrayList<>();

                for (int i = 0; i < areaBatchSize; i++) {
                    VesselData item = delegate.read();
                    if (item == null) {
                        break;
                    }
                    batch.add(item);
                }

                return batch.isEmpty() ? null : batch;
            }
        };
    }
    
    @Bean
    public StepExecutionListener areaStatisticsStepListener() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                // 누적된 데이터를 DB에 저장
                @SuppressWarnings("unchecked")
                List<AreaStatistics> statistics = (List<AreaStatistics>) 
                    stepExecution.getExecutionContext().get("areaStatistics");
                
                if (statistics != null && !statistics.isEmpty()) {
                    try {
                        upsertWriter.areaStatisticsWriter().write(
                            new Chunk<>(List.of(statistics))
                        );
                        
                        log.info("Successfully wrote {} area statistics", statistics.size());
                    } catch (Exception e) {
                        log.error("Failed to write area statistics", e);
                        throw new RuntimeException(e);
                    }
                }
                return stepExecution.getExitStatus();
            }
        };
    }
}