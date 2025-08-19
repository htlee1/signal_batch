package gc.mda.signal_batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.scheduling.TaskScheduler;
import gc.mda.signal_batch.global.config.WebSocketStompConfig;
import gc.mda.signal_batch.global.websocket.interceptor.TrackQueryInterceptor;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "scheduler.enabled=false",
    "spring.websocket.enabled=false"
})
@ActiveProfiles("test")
class SignalBatchApplicationTests {

    @MockBean
    private JobLauncher jobLauncher;
    
    @MockBean
    private JobRepository jobRepository;
    
    @MockBean
    private TaskScheduler taskScheduler;
    
    @MockBean
    private WebSocketStompConfig webSocketStompConfig;
    
    @MockBean
    private TrackQueryInterceptor trackQueryInterceptor;

    @Test
    void contextLoads() {
    }

}