package gc.mda.signal_batch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.RetryConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableRetry
public class RetryConfig {

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 재시도 정책
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(org.springframework.dao.DeadlockLoserDataAccessException.class, true);
        retryableExceptions.put(org.springframework.dao.CannotAcquireLockException.class, true);
        retryableExceptions.put(org.springframework.dao.TransientDataAccessResourceException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions);
        retryTemplate.setRetryPolicy(retryPolicy);

        // Backoff 정책
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(5000);
        backOffPolicy.setMultiplier(2);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}