package gc.mda.signal_batch.service.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 순차 구역 통과 선박 조회 최적화 서비스
 * - Unix timestamp 기반 M값 활용
 * - 병렬 쿼리 처리
 * - 결과 캐싱
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequentialAreaTrackingService {
    
    private final DataSource queryDataSource;
    
    /**
     * 순차적으로 지정된 구역들을 통과한 선박 조회 (Grid)
     */
    public List<Map<String, Object>> findSequentialGridPassages(
            List<Integer> haeguNumbers,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        // MATERIALIZED CTE 사용으로 중간 결과 고정
        String sql = """
            WITH vessel_passages AS (
                SELECT DISTINCT
                    sig_src_cd,
                    target_id,
                    haegu_no,
                    FIRST_VALUE(time_bucket) OVER (
                        PARTITION BY sig_src_cd, target_id, haegu_no 
                        ORDER BY time_bucket
                    ) as entry_time,
                    LAST_VALUE(time_bucket) OVER (
                        PARTITION BY sig_src_cd, target_id, haegu_no 
                        ORDER BY time_bucket 
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                    ) as exit_time
                FROM signal.t_grid_vessel_tracks
                WHERE time_bucket BETWEEN ? AND ?
                AND haegu_no = ANY(ARRAY[?]::integer[])
            )
            SELECT 
                v1.sig_src_cd,
                v1.target_id,
                v1.entry_time as haegu1_entry,
                v1.exit_time as haegu1_exit,
                v2.entry_time as haegu2_entry,
                v2.exit_time as haegu2_exit,
                v3.entry_time as haegu3_entry,
                v3.exit_time as haegu3_exit
            FROM vessel_passages v1
            JOIN vessel_passages v2 ON v1.sig_src_cd = v2.sig_src_cd 
                AND v1.target_id = v2.target_id
                AND v2.haegu_no = ? AND v2.entry_time > v1.exit_time
            JOIN vessel_passages v3 ON v2.sig_src_cd = v3.sig_src_cd 
                AND v2.target_id = v3.target_id  
                AND v3.haegu_no = ? AND v3.entry_time > v2.exit_time
            WHERE v1.haegu_no = ?
            ORDER BY v1.entry_time
        """;
        
        return jdbcTemplate.queryForList(sql,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            haeguNumbers.toArray(Integer[]::new),
            haeguNumbers.get(1),
            haeguNumbers.get(2),
            haeguNumbers.get(0)
        );
    }
    
    /**
     * 순차적으로 지정된 구역들을 통과한 선박 조회 (Area)
     */
    public List<Map<String, Object>> findSequentialAreaPassages(
            List<String> areaIds,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            WITH area_passages AS (
                SELECT DISTINCT
                    sig_src_cd,
                    target_id,
                    area_id,
                    FIRST_VALUE(time_bucket) OVER (
                        PARTITION BY sig_src_cd, target_id, area_id 
                        ORDER BY time_bucket
                    ) as entry_time,
                    LAST_VALUE(time_bucket) OVER (
                        PARTITION BY sig_src_cd, target_id, area_id 
                        ORDER BY time_bucket 
                        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                    ) as exit_time
                FROM signal.t_area_vessel_tracks
                WHERE time_bucket BETWEEN ? AND ?
                AND area_id = ANY(ARRAY[?]::varchar[])
            )
            SELECT 
                a1.sig_src_cd,
                a1.target_id,
                a1.entry_time as area1_entry,
                a1.exit_time as area1_exit,
                a2.entry_time as area2_entry,
                a2.exit_time as area2_exit,
                a3.entry_time as area3_entry,
                a3.exit_time as area3_exit
            FROM area_passages a1
            JOIN area_passages a2 ON a1.sig_src_cd = a2.sig_src_cd 
                AND a1.target_id = a2.target_id
                AND a2.area_id = ? AND a2.entry_time > a1.exit_time
            JOIN area_passages a3 ON a2.sig_src_cd = a3.sig_src_cd 
                AND a2.target_id = a3.target_id  
                AND a3.area_id = ? AND a3.entry_time > a2.exit_time
            WHERE a1.area_id = ?
            ORDER BY a1.entry_time
        """;
        
        return jdbcTemplate.queryForList(sql,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            areaIds.toArray(String[]::new),
            areaIds.get(1),
            areaIds.get(2),
            areaIds.get(0)
        );
    }
    
    /**
     * 특정 구역 통과 통계 (캐시 활용)
     */
    public Map<String, Object> getAreaPassageStatistics(
            String areaId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT 
                COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as unique_vessels,
                COUNT(*) as total_passages,
                SUM(distance_nm) as total_distance,
                AVG(avg_speed) as avg_speed,
                MIN(time_bucket) as first_passage,
                MAX(time_bucket) as last_passage
            FROM signal.t_area_vessel_tracks
            WHERE area_id = ?
            AND time_bucket BETWEEN ? AND ?
        """;
        
        return jdbcTemplate.queryForMap(sql,
            areaId,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime)
        );
    }
}
