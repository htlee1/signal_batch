package gc.mda.signal_batch.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * QueryDB 인덱스 상태 확인 테스트
 * 실행: mvn test -Dtest=IndexStatusTest
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
public class IndexStatusTest {

    @Autowired
    private JdbcTemplate queryJdbcTemplate;

    @Test
    public void checkCurrentIndexStatus() {
        log.info("=== QueryDB 인덱스 현황 확인 ===");
        
        // 주요 테이블 목록
        List<String> tables = List.of(
            "t_vessel_tracks_5min",
            "t_grid_vessel_tracks",
            "t_area_vessel_tracks",
            "t_vessel_tracks_hourly",
            "t_grid_tracks_summary_hourly",
            "t_area_tracks_summary_hourly",
            "t_vessel_tracks_daily",
            "t_grid_tracks_summary_daily",
            "t_area_tracks_summary_daily",
            "t_vessel_latest_position",
            "t_tile_summary",
            "t_area_statistics"
        );
        
        for (String table : tables) {
            log.info("\n### 테이블: {} ###", table);
            
            String sql = """
                SELECT 
                    indexname,
                    indexdef,
                    pg_size_pretty(pg_relation_size(indexname::regclass)) as size
                FROM pg_indexes
                WHERE schemaname = 'signal' AND tablename = ?
                ORDER BY indexname
            """;
            
            try {
                List<Map<String, Object>> indexes = queryJdbcTemplate.queryForList(sql, table);
                
                if (indexes.isEmpty()) {
                    log.warn("  인덱스가 없습니다!");
                } else {
                    for (Map<String, Object> idx : indexes) {
                        log.info("  - {}", idx.get("indexname"));
                        log.info("    정의: {}", idx.get("indexdef"));
                        log.info("    크기: {}", idx.get("size"));
                    }
                }
            } catch (Exception e) {
                log.error("  오류: {}", e.getMessage());
            }
        }
        
        // 권장 인덱스와 비교
        checkMissingIndexes();
    }
    
    private void checkMissingIndexes() {
        log.info("\n=== 누락된 인덱스 확인 ===");
        
        // 권장 인덱스 목록
        Map<String, List<String>> recommendedIndexes = Map.of(
            "t_vessel_tracks_5min", List.of(
                "idx_vessel_tracks_5min_vessel_time",
                "idx_vessel_tracks_5min_recent"
            ),
            "t_grid_vessel_tracks", List.of(
                "idx_grid_vessel_tracks_haegu_time_desc"
            ),
            "t_area_vessel_tracks", List.of(
                "idx_area_vessel_tracks_area_time_desc"
            ),
            "t_vessel_tracks_hourly", List.of(
                "idx_vessel_tracks_hourly_track_geom"
            ),
            "t_vessel_tracks_daily", List.of(
                "idx_vessel_tracks_daily_track_geom"
            )
        );
        
        for (Map.Entry<String, List<String>> entry : recommendedIndexes.entrySet()) {
            String table = entry.getKey();
            List<String> indexes = entry.getValue();
            
            for (String indexName : indexes) {
                String checkSql = """
                    SELECT COUNT(*) 
                    FROM pg_indexes 
                    WHERE schemaname = 'signal' 
                        AND tablename = ? 
                        AND indexname = ?
                """;
                
                Integer count = queryJdbcTemplate.queryForObject(checkSql, Integer.class, table, indexName);
                
                if (count == 0) {
                    log.warn("누락된 인덱스: {}.{}", table, indexName);
                } else {
                    log.info("존재하는 인덱스: {}.{}", table, indexName);
                }
            }
        }
    }
    
    @Test
    public void generateCreateIndexScript() {
        log.info("\n=== 인덱스 생성 스크립트 ===");
        
        List<String> createStatements = List.of(
            "-- 필수 인덱스 생성 스크립트",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vessel_tracks_5min_vessel_time ON signal.t_vessel_tracks_5min (sig_src_cd, target_id, time_bucket DESC);",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_grid_vessel_tracks_haegu_time_desc ON signal.t_grid_vessel_tracks (haegu_no, time_bucket DESC);",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_area_vessel_tracks_area_time_desc ON signal.t_area_vessel_tracks (area_id, time_bucket DESC);",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vessel_tracks_hourly_track_geom ON signal.t_vessel_tracks_hourly USING GIST (track_geom);",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vessel_tracks_daily_track_geom ON signal.t_vessel_tracks_daily USING GIST (track_geom);",
            "",
            "-- 인덱스 생성 후 통계 업데이트",
            "ANALYZE signal.t_vessel_tracks_5min;",
            "ANALYZE signal.t_grid_vessel_tracks;",
            "ANALYZE signal.t_area_vessel_tracks;",
            "ANALYZE signal.t_vessel_tracks_hourly;",
            "ANALYZE signal.t_vessel_tracks_daily;"
        );
        
        createStatements.forEach(log::info);
        
        assertNotNull(queryJdbcTemplate);
    }
}
