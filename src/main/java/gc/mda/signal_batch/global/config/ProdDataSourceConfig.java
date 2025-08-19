package gc.mda.signal_batch.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@Profile("prod")
public class ProdDataSourceConfig {

    private final DataSourceConfigProperties properties;

    public ProdDataSourceConfig(DataSourceConfigProperties properties) {
        this.properties = properties;
    }

    @Bean
    public DataSource collectDataSource() {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.getCollect().getJdbcUrl())
                .username(properties.getCollect().getUsername())
                .password(properties.getCollect().getPassword())
                .driverClassName(properties.getCollect().getDriverClassName())
                .build();
        return dataSource;
    }


    @Bean
    public DataSource queryDataSource() {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.getQuery().getJdbcUrl())
                .username(properties.getQuery().getUsername())
                .password(properties.getQuery().getPassword())
                .driverClassName(properties.getQuery().getDriverClassName())
                .build();
        return dataSource;
    }

    @Bean
    @Primary
    public DataSource batchDataSource() {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.getBatch().getJdbcUrl())
                .username(properties.getBatch().getUsername())
                .password(properties.getBatch().getPassword())
                .driverClassName(properties.getBatch().getDriverClassName())
                .build();
        return dataSource;
    }

    // Spring Batch가 찾는 기본 dataSource 빈
    @Bean
    public DataSource dataSource(@Qualifier("batchDataSource") DataSource batchDataSource) {
        return batchDataSource;
    }

    // Transaction Manager 설정
    @Bean
    public PlatformTransactionManager transactionManager(@Qualifier("collectDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager batchTransactionManager(@Qualifier("batchDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    // JdbcTemplate 빈 설정
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