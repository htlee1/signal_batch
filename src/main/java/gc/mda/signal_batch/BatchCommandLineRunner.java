package gc.mda.signal_batch;

import gc.mda.signal_batch.util.BatchUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@Profile("!test")  // 테스트 환경에서는 실행하지 않음
@RequiredArgsConstructor
public class BatchCommandLineRunner implements CommandLineRunner {

    @Autowired
    @Qualifier("asyncJobLauncher")  // 명시적으로 asyncJobLauncher 사용
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("vesselAggregationJob")
    private Job vesselAggregationJob;

    private final BatchUtils batchUtils;

    @Override
    public void run(String... args) throws Exception {
        // 명령행 인자로 배치 실행
        if (args.length > 0 && "run".equals(args[0])) {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusHours(1);

            if (args.length > 2) {
                startTime = LocalDateTime.parse(args[1]);
                endTime = LocalDateTime.parse(args[2]);
            }

            log.info("Running batch job from {} to {}", startTime, endTime);

            JobParameters params = batchUtils.createJobParameters(startTime, endTime);
            JobExecution execution = jobLauncher.run(vesselAggregationJob, params);

            log.info("Batch job completed: {}", execution.getStatus());
        } else {
            log.info("Batch application started. Use 'run' argument to execute job immediately.");
        }
    }
}