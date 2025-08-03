package gc.mda.signal_batch.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 데이터베이스 인덱스 자동 생성기
 * 애플리케이션 시작 시 --create.indexes=true 옵션으로 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "create.indexes", havingValue = "true")
public class IndexCreator implements CommandLineRunner {

    private final JdbcTemplate queryJdbcTemplate;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("=== 데이터베이스 인덱스 생성 시작 ===");
        
        try {
            // 1. 파티션 테이블 인덱스 생성 (상위 테이블에만 생성)
            createPartitionTableIndexes();
            
            // 2. 통계 업데이트
            updateStatistics();
            
            // 3. 생성 결과 확인
            verifyIndexCreation();
            
            log.info("=== 인덱스 생성 완료 ===");
            
        } catch (Exception e) {
            log.error("인덱스 생성 중 오류 발생", e);
            throw e;
        }
    }
    
    private void createPartitionTableIndexes() {
        log.info("파티션 테이블 인덱스 생성 중...");
        
        List<String> indexCreateStatements = List.of(
            // 5분 단위 궤적 테이블
            "CREATE INDEX IF NOT EXISTS idx_vessel_tracks_5min_vessel_time " +
            "ON signal.t_vessel_tracks_5min (sig_src_cd, target_id, time_bucket DESC)",
            
            // 해구별 궤적
            "CREATE INDEX IF NOT EXISTS idx_grid_vessel_tracks_haegu_time_desc " +
            "ON signal.t_grid_vessel_tracks (haegu_no, time_bucket DESC)",
            
            // 영역별 궤적
            "CREATE INDEX IF NOT EXISTS idx_area_vessel_tracks_area_time_desc " +
            "ON signal.t_area_vessel_tracks (area_id, time_bucket DESC)",
            
            // 시간별 궤적 공간 인덱스
            "CREATE INDEX IF NOT EXISTS idx_vessel_tracks_hourly_track_geom " +
            "ON signal.t_vessel_tracks_hourly USING GIST (track_geom)",
            
            // 일별 궤적 공간 인덱스
            "CREATE INDEX IF NOT EXISTS idx_vessel_tracks_daily_track_geom " +
            "ON signal.t_vessel_tracks_daily USING GIST (track_geom)",
            
            // 시간별 집계
            "CREATE INDEX IF NOT EXISTS idx_grid_tracks_summary_hourly_time_haegu " +
            "ON signal.t_grid_tracks_summary_hourly (time_bucket DESC, haegu_no)",
            
            "CREATE INDEX IF NOT EXISTS idx_area_tracks_summary_hourly_time_area " +
            "ON signal.t_area_tracks_summary_hourly (time_bucket DESC, area_id)",
            
            // 일별 집계
            "CREATE INDEX IF NOT EXISTS idx_grid_tracks_summary_daily_time_haegu " +
            "ON signal.t_grid_tracks_summary_daily (time_bucket DESC, haegu_no)",
            
            "CREATE INDEX IF NOT EXISTS idx_area_tracks_summary_daily_time_area " +
            "ON signal.t_area_tracks_summary_daily (time_bucket DESC, area_id)"
        );
        
        for (String sql : indexCreateStatements) {
            try {
                log.info("실행: {}", sql);
                queryJdbcTemplate.execute(sql);
                log.info("성공!");
            } catch (Exception e) {
                log.error("인덱스 생성 실패: {}", e.getMessage());
                // 이미 존재하는 경우는 무시
                if (!e.getMessage().contains("already exists")) {
                    throw e;
                }
            }
        }
    }
    
    private void updateStatistics() {
        log.info("테이블 통계 업데이트 중...");
        
        List<String> tables = List.of(
            "t_vessel_tracks_5min",
            "t_grid_vessel_tracks",
            "t_area_vessel_tracks",
            "t_vessel_tracks_hourly",
            "t_vessel_tracks_daily",
            "t_grid_tracks_summary_hourly",
            "t_area_tracks_summary_hourly",
            "t_grid_tracks_summary_daily",
            "t_area_tracks_summary_daily"
        );
        
        for (String table : tables) {
            try {
                String sql = "ANALYZE signal." + table;
                log.info("통계 업데이트: {}", table);
                queryJdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("통계 업데이트 실패 (무시): {} - {}", table, e.getMessage());
            }
        }
    }
    
    private void verifyIndexCreation() {
        log.info("\n=== 인덱스 생성 결과 확인 ===");
        
        String sql = """
            SELECT 
                tablename,
                COUNT(*) as index_count,
                STRING_AGG(indexname, ', ' ORDER BY indexname) as indexes
            FROM pg_indexes
            WHERE schemaname = 'signal'
                AND indexname LIKE ANY(ARRAY[
                    'idx_vessel_tracks_5min_vessel_time%',
                    'idx_grid_vessel_tracks_haegu_time_desc%',
                    'idx_area_vessel_tracks_area_time_desc%',
                    'idx_vessel_tracks_hourly_track_geom%',
                    'idx_vessel_tracks_daily_track_geom%',
                    'idx_grid_tracks_summary_hourly_time_haegu%',
                    'idx_area_tracks_summary_hourly_time_area%',
                    'idx_grid_tracks_summary_daily_time_haegu%',
                    'idx_area_tracks_summary_daily_time_area%'
                ])
            GROUP BY tablename
            ORDER BY tablename
        """;
        
        List<java.util.Map<String, Object>> results = queryJdbcTemplate.queryForList(sql);
        
        for (java.util.Map<String, Object> row : results) {
            log.info("테이블: {} - 인덱스 수: {} - 인덱스: {}", 
                row.get("tablename"), 
                row.get("index_count"), 
                row.get("indexes")
            );
        }
        
        // 파티션별 인덱스 현황
        String partitionSql = """
            SELECT 
                parent.relname AS parent_table,
                COUNT(DISTINCT child.relname) AS partition_count,
                COUNT(DISTINCT idx.relname) AS index_count
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
        
        log.info("\n파티션별 인덱스 현황:");
        List<java.util.Map<String, Object>> partitionResults = queryJdbcTemplate.queryForList(partitionSql);
        
        for (java.util.Map<String, Object> row : partitionResults) {
            log.info("파티션 테이블: {} - 파티션 수: {} - 총 인덱스 수: {}", 
                row.get("parent_table"), 
                row.get("partition_count"), 
                row.get("index_count")
            );
        }
    }
}
