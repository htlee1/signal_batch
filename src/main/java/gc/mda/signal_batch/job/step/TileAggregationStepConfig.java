package gc.mda.signal_batch.job.step;

import gc.mda.signal_batch.processor.AccumulatingTileProcessor;
import gc.mda.signal_batch.model.TileStatistics;
import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.processor.TileAggregationProcessor;
import gc.mda.signal_batch.reader.InMemoryVesselDataReader;
import gc.mda.signal_batch.reader.PartitionedReader;
import gc.mda.signal_batch.reader.VesselDataReader;
import gc.mda.signal_batch.writer.OptimizedBulkInsertWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TileAggregationStepConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager queryTransactionManager;
    private final VesselDataReader vesselDataReader;
    private final TileAggregationProcessor tileAggregationProcessor;
    private final AccumulatingTileProcessor accumulatingTileProcessor;
    private final OptimizedBulkInsertWriter optimizedBulkInsertWriter;
    private final PartitionedReader partitionedReader;
    private final ApplicationContext applicationContext;

    @Qualifier("batchTaskExecutor")
    private final TaskExecutor batchTaskExecutor;

    @Qualifier("partitionTaskExecutor")
    private final TaskExecutor partitionTaskExecutor;

    @Bean
    public Step aggregateTileStatisticsStep() {
        // InMemoryVesselDataReader를 ApplicationContext에서 가져옴
        InMemoryVesselDataReader inMemoryReader = applicationContext.getBean(InMemoryVesselDataReader.class);
        
        return new StepBuilder("aggregateTileStatisticsStep", jobRepository)
                .<VesselData, TileStatistics>chunk(50000, queryTransactionManager)
                .reader(inMemoryReader)  // 메모리 기반 Reader 사용
                .processor(accumulatingTileProcessor)
                .writer(new AccumulatedTileWriter())
                .listener(tileAggregationStepListener())
                .faultTolerant()
                .skipLimit(1000)
                .skip(Exception.class)
                .build();
    }
    
    @Bean
    @StepScope
    public ItemReader<VesselData> tileDataReader(
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
                    log.info("Creating tileDataReader with startTime: {}, endTime: {}", startTime, endTime);
                    
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
    public Step partitionedTileAggregationStep() {
        return new StepBuilder("partitionedTileAggregationStep", jobRepository)
                .partitioner("tileAggregationPartitioner", partitionedReader.dayPartitioner(null))
                .partitionHandler(tileAggregationPartitionHandler())
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler tileAggregationPartitionHandler() {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setTaskExecutor(partitionTaskExecutor);
        handler.setStep(tileAggregationSlaveStep());
        handler.setGridSize(24);
        return handler;
    }

    @Bean
    public Step tileAggregationSlaveStep() {
        return new StepBuilder("tileAggregationSlaveStep", jobRepository)
                .<List<VesselData>, List<TileStatistics>>chunk(50, queryTransactionManager)
                .reader(slaveTileBatchVesselDataReader(null, null, null))
                .processor(slaveTileProcessor(null, null))
                .writer(optimizedBulkInsertWriter.tileStatisticsBulkWriter())
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<List<VesselData>> tileBatchVesselDataReader(
            @Value("#{jobParameters['startTime']}") String startTimeStr,
            @Value("#{jobParameters['endTime']}") String endTimeStr) {
        LocalDateTime startTime = startTimeStr != null ? LocalDateTime.parse(startTimeStr) : null;
        LocalDateTime endTime = endTimeStr != null ? LocalDateTime.parse(endTimeStr) : null;
        return new ItemReader<List<VesselData>>() {
            private ItemReader<VesselData> delegate = vesselDataReader.vesselDataPagingReader(startTime, endTime, null);

            @Override
            public List<VesselData> read() throws Exception {
                List<VesselData> batch = new java.util.ArrayList<>();

                for (int i = 0; i < 1000; i++) {
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
    @StepScope
    public ItemReader<List<VesselData>> slaveTileBatchVesselDataReader(
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

                for (int i = 0; i < 1000; i++) {
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
    @StepScope
    public ItemProcessor<List<VesselData>, List<TileStatistics>> slaveTileProcessor(
            @Value("#{jobParameters['tileLevel']}") Integer tileLevel,
            @Value("#{jobParameters['timeBucketMinutes']}") Integer timeBucketMinutes) {

        final int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;

        // 여러 레벨 처리를 위한 복합 프로세서
        if (tileLevel == null) {
            CompositeItemProcessor<List<VesselData>, List<TileStatistics>> compositeProcessor =
                    new CompositeItemProcessor<>();

            compositeProcessor.setDelegates(Arrays.asList(
                    tileAggregationProcessor.batchProcessor(0, bucketMinutes),
                    tileAggregationProcessor.batchProcessor(1, bucketMinutes),
                    tileAggregationProcessor.batchProcessor(2, bucketMinutes)
            ));

            return compositeProcessor;
        } else {
            return tileAggregationProcessor.batchProcessor(tileLevel, bucketMinutes);
        }
    }

    @Bean
    @StepScope
    public ItemProcessor<VesselData, List<TileStatistics>> batchTileProcessor(
            @Value("#{jobParameters['tileLevel']}") Integer tileLevel,
            @Value("#{jobParameters['timeBucketMinutes']}") Integer timeBucketMinutes) {
        
        final int level = (tileLevel != null) ? tileLevel : 1;
        final int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;
        
        return new ItemProcessor<VesselData, List<TileStatistics>>() {
            private final List<VesselData> buffer = new ArrayList<>(1000);

            @Override
            public List<TileStatistics> process(VesselData item) throws Exception {
                if (item == null || !item.isValidPosition()) {
                    return null;
                }

                buffer.add(item);

                // 버퍼가 차면 처리
                if (buffer.size() >= 1000) {
                    List<TileStatistics> result = tileAggregationProcessor
                            .batchProcessor(level, bucketMinutes)
                            .process(new ArrayList<>(buffer));
                    buffer.clear();
                    return result;
                }

                return null;
            }
        };
    }
    
    /**
     * 누적된 결과를 한 번에 처리하는 Writer
     */
    private class AccumulatedTileWriter implements ItemWriter<TileStatistics> {
        @Override
        public void write(Chunk<? extends TileStatistics> chunk) throws Exception {
            // 대부분의 아이템은 null일 것임 (processor에서 null 반환)
            // 실제 데이터는 Step 종료 시 처리됨
            log.debug("AccumulatedTileWriter called with {} items", chunk.size());
        }
    }
    
    /**
     * Step 종료 후 누적된 데이터를 처리하는 리스너
     */
    @Bean
    @StepScope  
    public org.springframework.batch.core.StepExecutionListener tileAggregationStepListener() {
        return new org.springframework.batch.core.StepExecutionListener() {
            @Override
            public void beforeStep(org.springframework.batch.core.StepExecution stepExecution) {
                // beforeStep에서는 특별한 처리 없음
            }
            
            @Override
            public org.springframework.batch.core.ExitStatus afterStep(org.springframework.batch.core.StepExecution stepExecution) {
                log.info("[TileAggregationStepListener] afterStep called");
                
                try {
                    // AccumulatingTileProcessor에서 직접 결과 가져오기
                    List<TileStatistics> accumulatedTiles = accumulatingTileProcessor.getAccumulatedResults();
                    log.info("[TileAggregationStepListener] Retrieved {} tiles from processor", 
                            accumulatedTiles != null ? accumulatedTiles.size() : 0);
                    
                    if (accumulatedTiles != null && !accumulatedTiles.isEmpty()) {
                        log.info("Writing {} accumulated tiles to database", accumulatedTiles.size());
                        
                        // Bulk Writer를 사용하여 한 번에 저장
                        ItemWriter<List<TileStatistics>> writer = optimizedBulkInsertWriter.tileStatisticsBulkWriter();
                        Chunk<List<TileStatistics>> chunk = new Chunk<>();
                        chunk.add(accumulatedTiles);
                        writer.write(chunk);
                        
                        log.info("Successfully wrote all accumulated tiles");
                        stepExecution.setWriteCount(accumulatedTiles.size());
                    } else {
                        log.warn("[TileAggregationStepListener] No tiles to write!");
                    }
                    
                    return stepExecution.getExitStatus();
                } catch (Exception e) {
                    log.error("Failed to write accumulated tiles", e);
                    return org.springframework.batch.core.ExitStatus.FAILED;
                }
            }
        };
    }
}