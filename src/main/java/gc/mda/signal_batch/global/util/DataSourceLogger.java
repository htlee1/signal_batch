package gc.mda.signal_batch.global.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;


/**
 * DataSource 정보를 로깅하는 유틸리티 클래스
 */
@Slf4j
public class DataSourceLogger {

    /**
     * DataSource 상세 정보를 문자열로 반환
     */
    public static String getDataSourceInfo(DataSource dataSource) {
        if (dataSource == null) {
            return "DataSource is null";
        }
        
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String url = meta.getURL();
            String user = meta.getUserName();
            String db = conn.getCatalog();
            String schema = conn.getSchema();
            
            // Hikari 풀 이름 추출 시도
            String poolName = "Unknown";
            String dsClassName = dataSource.getClass().getName();
            if (dsClassName.contains("HikariDataSource")) {
                try {
                    // Reflection으로 풀 이름 가져오기
                    var poolNameMethod = dataSource.getClass().getMethod("getPoolName");
                    poolName = (String) poolNameMethod.invoke(dataSource);
                } catch (Exception e) {
                    // 무시
                }
            }
            
            return String.format("Pool=%s, URL=%s, User=%s, DB=%s, Schema=%s", 
                poolName, url, user, db, schema);
        } catch (Exception e) {
            return "Unknown DataSource (" + e.getMessage() + ")";
        }
    }

    /**
     * JdbcTemplate의 DataSource 정보와 현재 스키마 정보를 로깅
     */
    public static void logJdbcTemplateInfo(String componentName, JdbcTemplate jdbcTemplate) {
        try {
            DataSource dataSource = jdbcTemplate.getDataSource();
            String dsInfo = getDataSourceInfo(dataSource);
            
            // 현재 스키마 정보
            String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
            String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
            String searchPath = jdbcTemplate.queryForObject("SHOW search_path", String.class);
            
            log.info("{} - DataSource: {}", componentName, dsInfo);
            log.info("{} - Current DB: {}, Schema: {}, Search path: {}", 
                componentName, currentDb, currentSchema, searchPath);
            
        } catch (Exception e) {
            log.error("{} - Failed to get DataSource info: {}", componentName, e.getMessage());
        }
    }

    /**
     * 테이블 존재 여부 확인 및 로깅
     */
    public static boolean checkTableExists(String componentName, JdbcTemplate jdbcTemplate, 
                                         String schemaName, String tableName) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = ?)",
                Boolean.class,
                schemaName,
                tableName
            );
            
            log.info("{} - Table {}.{} exists: {}", componentName, schemaName, tableName, exists);
            
            if (!Boolean.TRUE.equals(exists)) {
                // 존재하는 테이블 목록 출력
                var tables = jdbcTemplate.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname = ? ORDER BY tablename",
                    String.class,
                    schemaName
                );
                log.info("{} - Available tables in schema '{}': {}", 
                    componentName, schemaName, String.join(", ", tables));
            }
            
            return Boolean.TRUE.equals(exists);
            
        } catch (Exception e) {
            log.error("{} - Failed to check table existence: {}", componentName, e.getMessage());
            return false;
        }
    }

    /**
     * 연결 테스트 쿼리 실행
     */
    public static void testConnection(String componentName, JdbcTemplate jdbcTemplate) {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("{} - Connection test successful: {}", componentName, result);
        } catch (Exception e) {
            log.error("{} - Connection test failed: {}", componentName, e.getMessage());
        }
    }
}