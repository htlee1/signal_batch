package gc.mda.signal_batch.performance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 인덱스 적용 상태 체크
 * 실행: --check.index.status=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "check.index.status", havingValue = "true")
public class IndexStatusChecker implements CommandLineRunner {

    @Autowired
    private JdbcTemplate queryJdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== 인덱스 적용 상태 확인 ===");
        
        // 1. 전체 인덱스 통계
        checkOverallIndexStatus();
        
        // 2. 테이블별 인덱스 상세
        checkTableIndexDetails();
        
        // 3. 파티션 테이블 인덱스 상태
        checkPartitionIndexStatus();
        
        // 4. 인덱스 사용 통계
        checkIndexUsageStats();
        
        // 5. 성능 개선 확인
        checkPerformanceMetrics();
    }
    
    private void checkOverallIndexStatus() {
        log.info("\n### 전체 인덱스 통계 ###");
        
        String sql = """
            SELECT 
                COUNT(DISTINCT tablename) as table_count,
                COUNT(*) as total_indexes,
                pg_size_pretty(SUM(pg_relation_size(indexname::regclass))) as total_size
            FROM pg_indexes
            WHERE schemaname = 'signal'
        """;
        
        Map<String, Object> stats = queryJdbcTemplate.queryForMap(sql);
        log.info("테이블 수: {}", stats.get("table_count"));
        log.info("전체 인덱스 수: {}", stats.get("total_indexes"));
        log.info("전체 인덱스 크기: {}", stats.get("total_size"));
    }
    
    private void checkTableIndexDetails() {
        log.info("\n### 주요 테이블 인덱스 상세 ###");
        
        List<String> targetTables = List.of(
            "t_vessel_tracks_5min",
            "t_grid_vessel_tracks",
            "t_area_vessel_tracks",
            "t_vessel_tracks_hourly",
            "t_vessel_tracks_daily"
        );
        
        for (String table : targetTables) {
            String sql = """
                SELECT 
                    indexname,
                    pg_size_pretty(pg_relation_size(indexname::regclass)) as size
                FROM pg_indexes
                WHERE schemaname = 'signal' AND tablename = ?
                ORDER BY indexname
            """;
            
            List<Map<String, Object>> indexes = queryJdbcTemplate.queryForList(sql, table);
            
            log.info("\n테이블: {}", table);
            for (Map<String, Object> idx : indexes) {
                log.info("  - {} ({})", idx.get("indexname"), idx.get("size"));
            }
        }
    }
    
    private void checkPartitionIndexStatus() {
        log.info("\n### 파티션 테이블 인덱스 상태 ###");
        
        String sql = """
            SELECT 
                parent.relname AS parent_table,
                COUNT(DISTINCT child.relname) AS partition_count,
                COUNT(DISTINCT idx.relname) AS total_indexes,
                pg_size_pretty(SUM(pg_relation_size(idx.oid))) as total_index_size
            FROM pg_inherits
                JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                JOIN pg_namespace nmsp ON parent.relnamespace = nmsp.oid
                LEFT JOIN pg_index i ON i.indrelid = child.oid
                LEFT JOIN pg_class idx ON i.indexrelid = idx.oid
            WHERE nmsp.nspname = 'signal'
                AND parent.relname IN (
                    't_vessel_tracks_5min', 
                    't_grid_vessel_tracks', 
                    't_area_vessel_tracks',
                    't_vessel_tracks_hourly',
                    't_vessel_tracks_daily'
                )
            GROUP BY parent.relname
            ORDER BY parent.relname
        """;
        
        List<Map<String, Object>> results = queryJdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> row : results) {
            log.info("\n파티션 테이블: {}", row.get("parent_table"));
            log.info("  파티션 수: {}", row.get("partition_count"));
            log.info("  총 인덱스 수: {}", row.get("total_indexes"));
            log.info("  인덱스 크기: {}", row.get("total_index_size"));
        }
    }
    
    private void checkIndexUsageStats() {
        log.info("\n### 인덱스 사용 통계 TOP 10 ###");
        
        String sql = """
            SELECT 
                schemaname,
                tablename,
                indexname,
                idx_scan,
                idx_tup_read,
                idx_tup_fetch,
                pg_size_pretty(pg_relation_size(indexrelid)) as size
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal'
            ORDER BY idx_scan DESC
            LIMIT 10
        """;
        
        List<Map<String, Object>> results = queryJdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> row : results) {
            log.info("인덱스: {} - 스캔: {} - 크기: {}", 
                row.get("indexname"), row.get("idx_scan"), row.get("size"));
        }
    }
    
    private void checkPerformanceMetrics() {
        log.info("\n### 성능 개선 확인 ###");
        
        // 권장 인덱스 적용 확인
        String checkSql = """
            SELECT 
                CASE 
                    WHEN EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='signal' AND indexname='idx_vessel_tracks_5min_vessel_time') 
                    THEN '✅' ELSE '❌' 
                END as vessel_time_idx,
                CASE 
                    WHEN EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='signal' AND indexname='idx_grid_vessel_tracks_haegu_time_desc') 
                    THEN '✅' ELSE '❌' 
                END as grid_haegu_idx,
                CASE 
                    WHEN EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='signal' AND indexname='idx_area_vessel_tracks_area_time_desc') 
                    THEN '✅' ELSE '❌' 
                END as area_time_idx,
                CASE 
                    WHEN EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='signal' AND indexname='idx_vessel_tracks_hourly_track_geom') 
                    THEN '✅' ELSE '❌' 
                END as hourly_geom_idx,
                CASE 
                    WHEN EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='signal' AND indexname='idx_vessel_tracks_daily_track_geom') 
                    THEN '✅' ELSE '❌' 
                END as daily_geom_idx
        """;
        
        Map<String, Object> status = queryJdbcTemplate.queryForMap(checkSql);
        
        log.info("\n권장 인덱스 적용 상태:");
        log.info("  선박별 시간 조회 인덱스: {}", status.get("vessel_time_idx"));
        log.info("  해구별 시간 조회 인덱스: {}", status.get("grid_haegu_idx"));
        log.info("  영역별 시간 조회 인덱스: {}", status.get("area_time_idx"));
        log.info("  시간별 공간 인덱스: {}", status.get("hourly_geom_idx"));
        log.info("  일별 공간 인덱스: {}", status.get("daily_geom_idx"));
        
        // Sequential Scan 비율 확인
        String seqScanSql = """
            SELECT 
                tablename,
                seq_scan,
                idx_scan,
                CASE WHEN seq_scan + idx_scan > 0 
                    THEN round(100.0 * idx_scan / (seq_scan + idx_scan), 2)
                    ELSE 0 
                END as index_usage_percent
            FROM pg_stat_user_tables
            WHERE schemaname = 'signal'
                AND tablename IN ('t_vessel_tracks_5min', 't_grid_vessel_tracks', 't_area_vessel_tracks')
                AND seq_scan + idx_scan > 100
            ORDER BY index_usage_percent DESC
        """;
        
        log.info("\n인덱스 사용률:");
        List<Map<String, Object>> scanStats = queryJdbcTemplate.queryForList(seqScanSql);
        for (Map<String, Object> row : scanStats) {
            log.info("  {} - 인덱스 사용률: {}%", 
                row.get("tablename"), row.get("index_usage_percent"));
        }
    }
}
