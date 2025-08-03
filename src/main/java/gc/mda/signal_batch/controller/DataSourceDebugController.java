package gc.mda.signal_batch.controller;

import gc.mda.signal_batch.util.DataSourceLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DataSource 연결 상태를 확인하는 디버그 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/debug")
@RequiredArgsConstructor
public class DataSourceDebugController {

    @Qualifier("collectDataSource")
    private final DataSource collectDataSource;
    
    @Qualifier("queryDataSource")
    private final DataSource queryDataSource;
    
    @Qualifier("batchDataSource")
    private final DataSource batchDataSource;
    
    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;
    
    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    @GetMapping("/datasources")
    public Map<String, Object> getDataSourceInfo() {
        Map<String, Object> result = new HashMap<>();
        
        // 각 DataSource 정보
        result.put("collectDataSource", getDataSourceDetails("collect", collectDataSource, collectJdbcTemplate));
        result.put("queryDataSource", getDataSourceDetails("query", queryDataSource, queryJdbcTemplate));
        result.put("batchDataSource", getDataSourceDetails("batch", batchDataSource, null));
        
        return result;
    }
    
    @GetMapping("/check-tables")
    public Map<String, Object> checkTables() {
        Map<String, Object> result = new HashMap<>();
        
        // Collect DB 테이블 확인
        result.put("collectDB", checkDataBaseTables(collectJdbcTemplate, "collect"));
        
        // Query DB 테이블 확인
        result.put("queryDB", checkDataBaseTables(queryJdbcTemplate, "query"));
        
        return result;
    }
    
    private Map<String, Object> getDataSourceDetails(String name, DataSource dataSource, JdbcTemplate jdbcTemplate) {
        Map<String, Object> details = new HashMap<>();
        
        try {
            // DataSource 기본 정보
            details.put("name", name);
            details.put("info", DataSourceLogger.getDataSourceInfo(dataSource));
            
            if (jdbcTemplate != null) {
                // 현재 데이터베이스 정보
                String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
                String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
                String searchPath = jdbcTemplate.queryForObject("SHOW search_path", String.class);
                
                details.put("currentDatabase", currentDb);
                details.put("currentSchema", currentSchema);
                details.put("searchPath", searchPath);
                
                // signal 스키마 존재 여부
                Boolean signalSchemaExists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = 'signal')",
                    Boolean.class
                );
                details.put("signalSchemaExists", signalSchemaExists);
                
                // 연결 테스트
                Integer testResult = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                details.put("connectionTest", testResult == 1 ? "OK" : "FAIL");
            }
            
        } catch (Exception e) {
            details.put("error", e.getMessage());
        }
        
        return details;
    }
    
    private Map<String, Object> checkDataBaseTables(JdbcTemplate jdbcTemplate, String dbType) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // signal 스키마의 테이블 목록
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                """
                SELECT 
                    tablename,
                    pg_size_pretty(pg_total_relation_size('signal.'||tablename)) as size,
                    CASE 
                        WHEN tablename LIKE 'sig_test%' THEN 'Source Data'
                        WHEN tablename = 't_vessel_latest_position' THEN 'Latest Position'
                        WHEN tablename = 't_areas' THEN 'Area Definition'
                        WHEN tablename LIKE 't_tile_summary%' THEN 'Tile Summary'
                        WHEN tablename LIKE 't_area_statistics%' THEN 'Area Statistics'
                        ELSE 'Other'
                    END as table_type
                FROM pg_tables
                WHERE schemaname = 'signal'
                ORDER BY table_type, tablename
                """
            );
            
            result.put("tables", tables);
            result.put("tableCount", tables.size());
            
            // 특정 테이블 존재 확인
            Map<String, Boolean> criticalTables = new HashMap<>();
            
            if ("collect".equals(dbType)) {
                // 수집 DB에 있어야 할 테이블
                criticalTables.put("sig_test", checkTableExists(jdbcTemplate, "signal", "sig_test"));
                String todayPartition = "sig_test_" + java.time.LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyMMdd")
                );
                criticalTables.put(todayPartition, checkTableExists(jdbcTemplate, "signal", todayPartition));
            } else {
                // 조회 DB에 있어야 할 테이블
                criticalTables.put("t_areas", checkTableExists(jdbcTemplate, "signal", "t_areas"));
                criticalTables.put("t_vessel_latest_position", checkTableExists(jdbcTemplate, "signal", "t_vessel_latest_position"));
                criticalTables.put("t_tile_summary", checkTableExists(jdbcTemplate, "signal", "t_tile_summary"));
                criticalTables.put("t_area_statistics", checkTableExists(jdbcTemplate, "signal", "t_area_statistics"));
            }
            
            result.put("criticalTables", criticalTables);
            
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    private boolean checkTableExists(JdbcTemplate jdbcTemplate, String schema, String table) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = ?)",
                Boolean.class,
                schema,
                table
            ));
        } catch (Exception e) {
            return false;
        }
    }
}