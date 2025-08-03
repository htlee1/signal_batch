package gc.mda.signal_batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "spring.datasource")
@Data
public class DataSourceConfigProperties {
    private DatabaseProperties collect = new DatabaseProperties();
    private DatabaseProperties query = new DatabaseProperties();
    private DatabaseProperties batch = new DatabaseProperties();

    @Data
    public static class DatabaseProperties {
        private String jdbcUrl;
        private String username;
        private String password;
        private String driverClassName = "org.postgresql.Driver";
    }
}