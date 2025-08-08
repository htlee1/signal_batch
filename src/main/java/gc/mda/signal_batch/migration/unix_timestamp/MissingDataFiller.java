package gc.mda.signal_batch.migration.unix_timestamp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * MIGRATION_V2: 누락된 track_geom_v2 데이터 채우기 유틸
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissingDataFiller {
    
    private final JdbcTemplate queryJdbcTemplate;
    
    @Transactional
    public int fillMissingHourlyData(LocalDateTime timeBucket) {
        String selectSql = """
            SELECT sig_src_cd, target_id, time_bucket::timestamp as time_bucket, ST_AsText(track_geom) as wkt
            FROM signal.t_vessel_tracks_hourly
            WHERE time_bucket = ?
              AND track_geom IS NOT NULL
              AND track_geom_v2 IS NULL
        """;
        
        List<Map<String, Object>> rows = queryJdbcTemplate.queryForList(selectSql, Timestamp.valueOf(timeBucket));
        
        String updateSql = """
            UPDATE signal.t_vessel_tracks_hourly
            SET track_geom_v2 = ST_GeomFromText(?, 4326)
            WHERE sig_src_cd = ? 
              AND target_id = ?
              AND time_bucket = ?
        """;
        
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                String sigSrcCd = (String) row.get("sig_src_cd");
                String targetId = (String) row.get("target_id");
                Object timeBucketObj = row.get("time_bucket");
                LocalDateTime bucket = null;
                
                if (timeBucketObj instanceof Timestamp) {
                    bucket = ((Timestamp) timeBucketObj).toLocalDateTime();
                } else if (timeBucketObj instanceof java.sql.Date) {
                    bucket = ((java.sql.Date) timeBucketObj).toLocalDate().atStartOfDay();
                }
                
                String wkt = (String) row.get("wkt");
                
                String convertedWkt = convertWktToUnixTimestamp(wkt, bucket);
                
                queryJdbcTemplate.update(updateSql, convertedWkt, sigSrcCd, targetId, Timestamp.valueOf(bucket));
                count++;
            } catch (Exception e) {
                log.warn("Failed to process row: {}", row, e);
            }
        }
        
        log.info("Filled {} missing hourly records for {}", count, timeBucket);
        return count;
    }
    
    @Transactional
    public int fillMissingDailyData(LocalDateTime date) {
        LocalDateTime dayStart = date.toLocalDate().atStartOfDay();
        
        String selectSql = """
            SELECT sig_src_cd, target_id, time_bucket::timestamp as time_bucket, ST_AsText(track_geom) as wkt
            FROM signal.t_vessel_tracks_daily
            WHERE time_bucket = ?
              AND track_geom IS NOT NULL
              AND track_geom_v2 IS NULL
        """;
        
        List<Map<String, Object>> rows = queryJdbcTemplate.queryForList(selectSql, Timestamp.valueOf(dayStart));
        
        String updateSql = """
            UPDATE signal.t_vessel_tracks_daily
            SET track_geom_v2 = ST_GeomFromText(?, 4326)
            WHERE sig_src_cd = ? 
              AND target_id = ?
              AND time_bucket = ?
        """;
        
        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                String sigSrcCd = (String) row.get("sig_src_cd");
                String targetId = (String) row.get("target_id");
                Object timeBucketObj = row.get("time_bucket");
                LocalDateTime bucket = null;
                
                if (timeBucketObj instanceof Timestamp) {
                    bucket = ((Timestamp) timeBucketObj).toLocalDateTime();
                } else if (timeBucketObj instanceof java.sql.Date) {
                    bucket = ((java.sql.Date) timeBucketObj).toLocalDate().atStartOfDay();
                }
                
                String wkt = (String) row.get("wkt");
                
                String convertedWkt = convertWktToUnixTimestamp(wkt, bucket);
                
                queryJdbcTemplate.update(updateSql, convertedWkt, sigSrcCd, targetId, Timestamp.valueOf(bucket));
                count++;
            } catch (Exception e) {
                log.warn("Failed to process row: {}", row, e);
            }
        }
        
        log.info("Filled {} missing daily records for {}", count, dayStart);
        return count;
    }
    
    private String convertWktToUnixTimestamp(String wkt, LocalDateTime baseTime) {
        // 공백 처리 - "LINESTRING M(" 또는 "LINESTRING M (" 모두 처리
        String coords = wkt.replace("LINESTRING M (", "")
                          .replace("LINESTRING M(", "")
                          .replace(")", "")
                          .trim();
        String[] points = coords.split(",");
        
        long unixBase = baseTime.toEpochSecond(ZoneOffset.of("+09:00"));
        StringBuilder result = new StringBuilder("LINESTRING M(");
        
        // 단일 포인트 처리 (중복된 포인트인 경우)
        boolean isSinglePoint = false;
        if (points.length == 2) {
            String p1 = points[0].trim();
            String p2 = points[1].trim();
            if (p1.equals(p2)) {
                isSinglePoint = true;
            }
        }
        
        for (int i = 0; i < points.length; i++) {
            if (i > 0) result.append(",");
            String point = points[i].trim();
            String[] parts = point.split("\\s+");
            
            if (parts.length < 3) {
                log.warn("Invalid point format: {}", point);
                continue;
            }
            
            long relativeSeconds = Long.parseLong(parts[2]);
            long unixTime = unixBase + relativeSeconds;
            result.append(parts[0]).append(" ").append(parts[1]).append(" ").append(unixTime);
        }
        
        // PostGIS는 LINESTRING에 최소 2개 포인트 필요 - 단일 포인트면 중복 추가
        if (isSinglePoint) {
            String lastPoint = result.substring(result.indexOf("(") + 1);
            result.append(",").append(lastPoint);
        }
        
        result.append(")");
        return result.toString();
    }
}
