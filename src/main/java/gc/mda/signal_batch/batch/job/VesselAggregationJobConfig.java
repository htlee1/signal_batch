package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.global.util.SharedDataJobListener;
import gc.mda.signal_batch.global.util.VesselDataHolder;
import gc.mda.signal_batch.batch.listener.JobCompletionListener;
import gc.mda.signal_batch.batch.listener.PerformanceOptimizationListener;
import gc.mda.signal_batch.batch.reader.InMemoryVesselDataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Slf4j
@Configuration
@RequiredArgsConstructor
public class VesselAggregationJobConfig {

    private final JobRepository jobRepository;
    private final LatestPositionStepConfig latestPositionStepConfig;
    private final TileAggregationStepConfig tileAggregationStepConfig;
    private final AreaStatisticsStepConfig areaStatisticsStepConfig;
    private final JobCompletionListener jobCompletionListener;
    private final SharedDataJobListener sharedDataJobListener;
    private final VesselDataHolder vesselDataHolder;
    private final PerformanceOptimizationListener performanceOptimizationListener;

    @Bean
    public Job vesselAggregationJob() {
        return new JobBuilder("vesselAggregationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(jobParametersValidator())
                .listener(jobCompletionListener)
                .listener(sharedDataJobListener)  // 데이터 로드 리스너 추가
                .listener(performanceOptimizationListener)  // 성능 최적화 리스너 추가
                .start(latestPositionStepConfig.updateLatestPositionStep())
                .next(tileAggregationStepConfig.aggregateTileStatisticsStep())
                .next(areaStatisticsStepConfig.aggregateAreaStatisticsStep())
                .build();
    }
    
    @Bean
    @StepScope
    public InMemoryVesselDataReader inMemoryVesselDataReader() {
        return new InMemoryVesselDataReader(vesselDataHolder);
    }

    @Bean
    public Job vesselDailyPositionJob() {
        return new JobBuilder("vesselDailyPositionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobCompletionListener)
                .start(latestPositionStepConfig.partitionedLatestPositionStep())
                .next(tileAggregationStepConfig.partitionedTileAggregationStep())
                .next(areaStatisticsStepConfig.partitionedAreaStatisticsStep())
                .build();
    }

    @Bean
    public JobParametersValidator jobParametersValidator() {
        DefaultJobParametersValidator validator = new DefaultJobParametersValidator();
        validator.setRequiredKeys(new String[]{"startTime", "endTime"});
        validator.setOptionalKeys(new String[]{"executionTime", "processingDate",
                "tileLevel", "partitionCount"});
        return validator;
    }
}