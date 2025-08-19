package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.batch.listener.JobCompletionListener;
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
public class DailyAggregationJobConfig {
    
    private final JobRepository jobRepository;
    private final DailyAggregationStepConfig dailyAggregationStepConfig;
    private final JobCompletionListener jobCompletionListener;
    
    @Bean
    public Job dailyAggregationJob() {
        return new JobBuilder("dailyAggregationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(dailyJobParametersValidator())
                .listener(jobCompletionListener)
                .start(dailyAggregationStepConfig.mergeDailyTracksStep())
                .next(dailyAggregationStepConfig.gridDailySummaryStep())
                .next(dailyAggregationStepConfig.areaDailySummaryStep())
                .build();
    }
    
    @Bean
    public JobParametersValidator dailyJobParametersValidator() {
        DefaultJobParametersValidator validator = new DefaultJobParametersValidator();
        validator.setRequiredKeys(new String[]{"startTime", "endTime", "timeBucket"});
        validator.setOptionalKeys(new String[]{"executionTime", "enableAbnormalDetection"});
        return validator;
    }
}