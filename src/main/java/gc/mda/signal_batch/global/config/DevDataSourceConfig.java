package gc.mda.signal_batch.global.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Slf4j
@Configuration
@Profile("dev")
public class DevDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.collect")
    public HikariConfig collectHikariConfig() {
        HikariConfig config = new HikariConfig();
        // 여기서 기본값을 설정하면 yml 파일의 설정과 병합됨
        config.setConnectionInitSql("SET TIME ZONE 'Asia/Seoul'; SET search_path TO signal, public;");
        return config;
    }

    @Bean
    public DataSource collectDataSource(@Qualifier("collectHikariConfig") HikariConfig hikariConfig) {
        // HikariConfig는 이미 @ConfigurationProperties로 설정이 주입되어 있음
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        
        log.info("Collect DataSource created:");
        log.info("  - URL: {}", hikariConfig.getJdbcUrl());
        log.info("  - Connection Init SQL: {}", hikariConfig.getConnectionInitSql());
        log.info("  - Pool Name: {}", hikariConfig.getPoolName());
        
        // PostGIS 타입 등록 (선택사항)
        try {
            PostGISConfig.registerPostGISTypes(dataSource);
        } catch (Exception e) {
            log.warn("PostGIS type registration skipped: {}", e.getMessage());
        }
        
        return dataSource;
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.query")
    public HikariConfig queryHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setConnectionInitSql("SET TIME ZONE 'Asia/Seoul'; SET search_path TO signal, public;");
        return config;
    }

    @Bean
    public DataSource queryDataSource(@Qualifier("queryHikariConfig") HikariConfig hikariConfig) {
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        
        log.info("Query DataSource created:");
        log.info("  - URL: {}", hikariConfig.getJdbcUrl());
        log.info("  - Connection Init SQL: {}", hikariConfig.getConnectionInitSql());
        log.info("  - Pool Name: {}", hikariConfig.getPoolName());
        
        // PostGIS 타입 등록 (선택사항)
        try {
            PostGISConfig.registerPostGISTypes(dataSource);
        } catch (Exception e) {
            log.warn("PostGIS type registration skipped: {}", e.getMessage());
        }
        
        return dataSource;
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.batch")
    public HikariConfig batchHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setConnectionInitSql("SET TIME ZONE 'Asia/Seoul'");
        return config;
    }

    @Bean
    @Primary
    public DataSource batchDataSource(@Qualifier("batchHikariConfig") HikariConfig hikariConfig) {
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        
        log.info("Batch DataSource created:");
        log.info("  - URL: {}", hikariConfig.getJdbcUrl());
        log.info("  - Connection Init SQL: {}", hikariConfig.getConnectionInitSql());
        log.info("  - Pool Name: {}", hikariConfig.getPoolName());
        
        // PostGIS 타입 등록 (선택사항)
        try {
            PostGISConfig.registerPostGISTypes(dataSource);
        } catch (Exception e) {
            log.warn("PostGIS type registration skipped: {}", e.getMessage());
        }
        
        return dataSource;
    }

    @Bean
    public DataSource dataSource(@Qualifier("batchDataSource") DataSource batchDataSource) {
        return batchDataSource;
    }

    @Bean
    public PlatformTransactionManager transactionManager(@Qualifier("collectDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
    
    @Bean
    public PlatformTransactionManager queryTransactionManager(@Qualifier("queryDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager batchTransactionManager(@Qualifier("batchDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "collectJdbcTemplate")
    public JdbcTemplate collectJdbcTemplate(@Qualifier("collectDataSource") DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setFetchSize(10000);
        jdbcTemplate.setQueryTimeout(300);
        return jdbcTemplate;
    }

    @Bean(name = "queryJdbcTemplate")
    public JdbcTemplate queryJdbcTemplate(@Qualifier("queryDataSource") DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(300);
        return jdbcTemplate;
    }

    @Bean(name = "collectNamedJdbcTemplate")
    public NamedParameterJdbcTemplate collectNamedJdbcTemplate(@Qualifier("collectDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean(name = "queryNamedJdbcTemplate")
    public NamedParameterJdbcTemplate queryNamedJdbcTemplate(@Qualifier("queryDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}