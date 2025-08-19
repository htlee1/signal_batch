package gc.mda.signal_batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SignalBatchApplication {

    public static void main(String[] args) {
        // JVM 기본 시간대를 KST로 설정 (DB 데이터와 일치)
        System.setProperty("user.timezone", "Asia/Seoul");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        
        // 중요: PostgreSQL JDBC 드라이버가 timestamp를 올바르게 처리하도록 설정
        // timestamp (without time zone) 타입을 LocalDateTime으로 매핑
        System.setProperty("jdbc.timestamp.tz", "false");
        
        SpringApplication.run(SignalBatchApplication.class, args);
    }

}