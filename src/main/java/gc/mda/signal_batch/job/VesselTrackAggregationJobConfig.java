package gc.mda.signal_batch.job;

import gc.mda.signal_batch.common.VesselTrackDataJobListener;
import gc.mda.signal_batch.job.listener.JobCompletionListener;
import gc.mda.signal_batch.job.listener.PerformanceOptimizationListener;
import gc.mda.signal_batch.job.step.VesselTrackStepConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class VesselTrackAggregationJobConfig {
    
    private final JobRepository jobRepository;
    private final VesselTrackStepConfig vesselTrackStepConfig;
    private final JobCompletionListener jobCompletionListener;
    private final VesselTrackDataJobListener vesselTrackDataJobListener;
    private final PerformanceOptimizationListener performanceOptimizationListener;
    
    @Bean
    public Job vesselTrackAggregationJob() {
        return new JobBuilder("vesselTrackAggregationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(trackJobParametersValidator())
                .listener(jobCompletionListener)
                .listener(vesselTrackDataJobListener)
                .listener(performanceOptimizationListener)  // 성능 최적화 리스너 추가
                .start(vesselTrackStepConfig.vesselTrackStep())
                .next(vesselTrackStepConfig.gridTrackSummaryStep())
                .next(vesselTrackStepConfig.areaTrackSummaryStep())
                .build();
    }
    
    @Bean
    public JobParametersValidator trackJobParametersValidator() {
        DefaultJobParametersValidator validator = new DefaultJobParametersValidator();
        validator.setRequiredKeys(new String[]{"startTime", "endTime", "timeBucket"});
        validator.setOptionalKeys(new String[]{"executionTime", "processingDate"});
        return validator;
    }
}
