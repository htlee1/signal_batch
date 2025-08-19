package gc.mda.signal_batch.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
@Profile("test")
public class TestDataSourceConfig {

    @Bean
    @Primary
    public DataSource collectDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:collectdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");
        config.setPoolName("CollectPool-Test");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    @Bean
    public DataSource queryDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:querydb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");
        config.setPoolName("QueryPool-Test");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    @Bean
    public DataSource batchDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:batchdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        config.setPassword("");
        config.setPoolName("BatchPool-Test");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }
}