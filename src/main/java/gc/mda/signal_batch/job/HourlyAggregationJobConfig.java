package gc.mda.signal_batch.job;

import gc.mda.signal_batch.job.listener.JobCompletionListener;
import gc.mda.signal_batch.job.step.HourlyAggregationStepConfig;
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
public class HourlyAggregationJobConfig {
    
    private final JobRepository jobRepository;
    private final HourlyAggregationStepConfig hourlyAggregationStepConfig;
    private final JobCompletionListener jobCompletionListener;
    
    @Bean
    public Job hourlyAggregationJob() {
        return new JobBuilder("hourlyAggregationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(hourlyJobParametersValidator())
                .listener(jobCompletionListener)
                .start(hourlyAggregationStepConfig.mergeHourlyTracksStep())
                .next(hourlyAggregationStepConfig.gridHourlySummaryStep())
                .next(hourlyAggregationStepConfig.areaHourlySummaryStep())
                .build();
    }
    
    @Bean
    public JobParametersValidator hourlyJobParametersValidator() {
        DefaultJobParametersValidator validator = new DefaultJobParametersValidator();
        validator.setRequiredKeys(new String[]{"startTime", "endTime", "timeBucket"});
        validator.setOptionalKeys(new String[]{"executionTime", "enableAbnormalDetection"});
        return validator;
    }
}
