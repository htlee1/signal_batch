package gc.mda.signal_batch.migration.unix_timestamp;

import gc.mda.signal_batch.controller.MigrationController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
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
    private final JdbcTemplate collectJdbcTemplate;  // collectDB 연결 추가
    private final JobLauncher jobLauncher;
    
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
        
        // Unix timestamp는 UTC 기준
        long unixBase = baseTime.toEpochSecond(ZoneOffset.UTC);
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
    
    /**
     * Unix timestamp 검증 및 자동 수정 (누락된 time_bucket 생성 포함)
     */
    @Transactional
    public MigrationController.FixResult fixUnixTimestamps(String tableName, LocalDateTime startTime, LocalDateTime endTime, int batchSize) {
        MigrationController.FixResult result = new MigrationController.FixResult();
        result.processedCount = 0;
        result.filledCount = 0;
        result.correctedCount = 0;
        result.skippedCount = 0;
        result.createdBuckets = 0;
        
        String fullTableName = getFullTableName(tableName);
        
        // 예상되는 time_bucket 생성
        List<LocalDateTime> expectedBuckets = generateExpectedBuckets(tableName, startTime, endTime, batchSize);
        
        for (LocalDateTime timeBucket : expectedBuckets) {
            result.processedCount++;
            result.lastTimeBucket = timeBucket;
            
            // time_bucket 존재 여부 확인
            String countSql = String.format("""
                SELECT COUNT(*) FROM %s WHERE time_bucket = ?
            """, fullTableName);
            
            Integer count = queryJdbcTemplate.queryForObject(countSql, Integer.class, Timestamp.valueOf(timeBucket));
            
            if (count == 0) {
                // time_bucket 자체가 없음 - 생성 필요
                log.info("[{}] {} - No data found, creating new aggregation", tableName, timeBucket);
                int created = createMissingBucket(tableName, timeBucket);
                result.createdBuckets += created;
                log.info("[{}] {} - Created {} records", tableName, timeBucket, created);
                continue;
            }
            
            // 해당 time_bucket의 모든 레코드 확인
            String checkSql = String.format("""
                SELECT sig_src_cd, target_id,
                       track_geom IS NOT NULL as has_v1,
                       track_geom_v2 IS NOT NULL as has_v2,
                       CASE WHEN track_geom_v2 IS NOT NULL 
                            THEN ST_M(ST_PointN(track_geom_v2, 1))::bigint 
                            ELSE NULL END as first_m,
                       EXTRACT(EPOCH FROM (start_position->>'time')::timestamp)::bigint as expected_unix,
                       ST_AsText(track_geom) as wkt_v1,
                       ST_AsText(track_geom_v2) as wkt_v2
                FROM %s
                WHERE time_bucket = ?
            """, fullTableName);
            
            List<Map<String, Object>> records = queryJdbcTemplate.queryForList(checkSql, Timestamp.valueOf(timeBucket));
            
            int bucketFilled = 0, bucketCorrected = 0, bucketSkipped = 0;
            
            for (Map<String, Object> record : records) {
                String sigSrcCd = (String) record.get("sig_src_cd");
                String targetId = (String) record.get("target_id");
                boolean hasV1 = (Boolean) record.get("has_v1");
                boolean hasV2 = (Boolean) record.get("has_v2");
                
                if (!hasV1) {
                    bucketSkipped++;
                    continue;
                }
                
                if (!hasV2) {
                    // track_geom_v2가 비어있음 - 채우기
                    String wktV1 = (String) record.get("wkt_v1");
                    String convertedWkt = convertWktToUnixTimestamp(wktV1, timeBucket);
                    
                    String updateSql = String.format("""
                        UPDATE %s
                        SET track_geom_v2 = ST_GeomFromText(?, 4326)
                        WHERE sig_src_cd = ? AND target_id = ? AND time_bucket = ?
                    """, fullTableName);
                    
                    queryJdbcTemplate.update(updateSql, convertedWkt, sigSrcCd, targetId, Timestamp.valueOf(timeBucket));
                    bucketFilled++;
                    
                } else {
                    // track_geom_v2가 있음 - M값 검증
                    Long firstM = (Long) record.get("first_m");
                    Long expectedUnix = (Long) record.get("expected_unix");
                    
                    if (firstM != null && expectedUnix != null) {
                        long diff = firstM - expectedUnix;
                        
                        // 30000초(8.3시간) 이상 차이나면 수정
                        if (Math.abs(diff) >= 30000) {
                            String wktV2 = (String) record.get("wkt_v2");
                            
                            // 가장 가까운 보정값 계산
                            long adjustment = 0;
                            if (Math.abs(diff - 32400) < 3000) { // 9시간 차이
                                adjustment = -32400;
                            } else if (Math.abs(diff + 32400) < 3000) { // -9시간 차이
                                adjustment = 32400;
                            } else if (Math.abs(diff - 64800) < 3000) { // 18시간 차이
                                adjustment = -64800;
                            } else if (Math.abs(diff + 64800) < 3000) { // -18시간 차이
                                adjustment = 64800;
                            } else {
                                // 기타 차이 - 원본 값으로 복원
                                adjustment = -diff;
                            }
                            
                            String correctedWkt = correctUnixTimestamp(wktV2, adjustment);
                            
                            String updateSql = String.format("""
                                UPDATE %s
                                SET track_geom_v2 = ST_GeomFromText(?, 4326)
                                WHERE sig_src_cd = ? AND target_id = ? AND time_bucket = ?
                            """, fullTableName);
                            
                            queryJdbcTemplate.update(updateSql, correctedWkt, sigSrcCd, targetId, Timestamp.valueOf(timeBucket));
                            bucketCorrected++;
//                            log.debug("[{}] {} - Corrected {}/{} (diff: {}s, adjustment: {}s)",
//                                tableName, timeBucket, sigSrcCd, targetId, diff, adjustment);
                        } else {
                            // 30000초 미만 차이는 정상
                            bucketSkipped++;
                        }
                    }
                }
            }
            
            // time_bucket별 결과 로깅
            if (bucketFilled > 0) {
                log.info("[{}] {} - track_geom_v2 비어있던 {}건 채움", tableName, timeBucket, bucketFilled);
            }
            if (bucketCorrected > 0) {
                log.info("[{}] {} - track_geom_v2 잘못 저장된 {}건 수정", tableName, timeBucket, bucketCorrected);
            }
            if (bucketFilled == 0 && bucketCorrected == 0) {
                log.info("[{}] {} - 문제없음 (전체 {}건)", tableName, timeBucket, records.size());
            }
            
            result.filledCount += bucketFilled;
            result.correctedCount += bucketCorrected;
            result.skippedCount += bucketSkipped;
        }
        
        return result;
    }
    
    /**
     * 예상되는 time_bucket 목록 생성
     */
    private List<LocalDateTime> generateExpectedBuckets(String tableName, LocalDateTime startTime, LocalDateTime endTime, int maxBatchSize) {
        List<LocalDateTime> buckets = new java.util.ArrayList<>();
        LocalDateTime current = startTime;
        
        int count = 0;
        while (!current.isAfter(endTime) && count < maxBatchSize) {
            buckets.add(current);
            count++;
            
            switch (tableName.toLowerCase()) {
                case "5min":
                    current = current.plusMinutes(5);
                    break;
                case "hourly":
                    current = current.plusHours(1);
                    break;
                case "daily":
                    current = current.plusDays(1);
                    break;
            }
        }
        
        log.info("[{}] Generated {} buckets from {} to {}", 
            tableName, buckets.size(), startTime, buckets.isEmpty() ? startTime : buckets.get(buckets.size()-1));
        
        return buckets;
    }
    
    /**
     * 누락된 time_bucket 생성 (배치 job과 동일한 작업)
     */
    private int createMissingBucket(String tableName, LocalDateTime timeBucket) {
        switch (tableName.toLowerCase()) {
            case "5min":
                return createMissing5min(timeBucket);
            case "hourly":
                return createMissingHourly(timeBucket);
            case "daily":
                return createMissingDaily(timeBucket);
            default:
                log.warn("Cannot create missing bucket for table: {}", tableName);
                return 0;
        }
    }

    private int createMissing5min(LocalDateTime timeBucket) {
        // 5min 데이터는 기존 VesselTrackAggregationJob과 동일한 방식으로 처리
        // collectDB에서 직접 집계하지 않고 기존 배치 잡 호출
        log.warn("[5min] {} - Cannot create 5min data directly. Run vesselTrackAggregationJob instead.", timeBucket);

                // 대안: 기존 5min 배치 잡을 트리거하거나 수동 실행 필요
                // 여기서는 단순히 로그만 남기고 0 반환
        return 0;
    }
    
    private int createMissingHourly(LocalDateTime hourBucket) {
        // 5min 데이터를 hourly로 집계
        String sql = """
            INSERT INTO signal.t_vessel_tracks_hourly (
                sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            )
            WITH aggregated AS (
                SELECT 
                    sig_src_cd, target_id,
                    ?::timestamp as time_bucket,
                    ST_MakeLine(track_geom ORDER BY time_bucket) as track_geom,
                    ST_MakeLine(track_geom_v2 ORDER BY time_bucket) as track_geom_v2,
                    SUM(distance_nm) as distance_nm,
                    MAX(max_speed) as max_speed,
                    SUM(point_count) as point_count,
                    MIN(time_bucket) as first_time,
                    MAX(time_bucket) as last_time
                FROM signal.t_vessel_tracks_5min
                WHERE time_bucket >= ? AND time_bucket < ?
                  AND track_geom IS NOT NULL
                GROUP BY sig_src_cd, target_id
            )
            SELECT 
                a.sig_src_cd, a.target_id, a.time_bucket,
                a.track_geom, a.track_geom_v2, a.distance_nm,
                CASE WHEN EXTRACT(EPOCH FROM (a.last_time - a.first_time)) > 0
                     THEN (a.distance_nm / (EXTRACT(EPOCH FROM (a.last_time - a.first_time)) / 3600.0))::numeric(6,2)
                     ELSE 0 END as avg_speed,
                a.max_speed, a.point_count,
                (SELECT start_position FROM signal.t_vessel_tracks_5min 
                 WHERE sig_src_cd = a.sig_src_cd AND target_id = a.target_id 
                   AND time_bucket = a.first_time) as start_position,
                (SELECT end_position FROM signal.t_vessel_tracks_5min 
                 WHERE sig_src_cd = a.sig_src_cd AND target_id = a.target_id 
                   AND time_bucket = a.last_time) as end_position
            FROM aggregated a
        """;
        
        return queryJdbcTemplate.update(sql, 
            Timestamp.valueOf(hourBucket),
            Timestamp.valueOf(hourBucket),
            Timestamp.valueOf(hourBucket.plusHours(1)));
    }
    
    private int createMissingDaily(LocalDateTime dayBucket) {
        // hourly 데이터를 daily로 집계
        String sql = """
            INSERT INTO signal.t_vessel_tracks_daily (
                sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            )
            WITH aggregated AS (
                SELECT 
                    sig_src_cd, target_id,
                    ?::timestamp as time_bucket,
                    ST_MakeLine(track_geom ORDER BY time_bucket) as track_geom,
                    ST_MakeLine(track_geom_v2 ORDER BY time_bucket) as track_geom_v2,
                    SUM(distance_nm) as distance_nm,
                    MAX(max_speed) as max_speed,
                    SUM(point_count) as point_count,
                    MIN(time_bucket) as first_time,
                    MAX(time_bucket) as last_time
                FROM signal.t_vessel_tracks_hourly
                WHERE time_bucket >= ? AND time_bucket < ?
                  AND track_geom IS NOT NULL
                GROUP BY sig_src_cd, target_id
            )
            SELECT 
                a.sig_src_cd, a.target_id, a.time_bucket,
                a.track_geom, a.track_geom_v2, a.distance_nm,
                CASE WHEN EXTRACT(EPOCH FROM (a.last_time - a.first_time)) > 0
                     THEN (a.distance_nm / (EXTRACT(EPOCH FROM (a.last_time - a.first_time)) / 3600.0))::numeric(6,2)
                     ELSE 0 END as avg_speed,
                a.max_speed, a.point_count,
                (SELECT start_position FROM signal.t_vessel_tracks_hourly 
                 WHERE sig_src_cd = a.sig_src_cd AND target_id = a.target_id 
                   AND time_bucket = a.first_time) as start_position,
                (SELECT end_position FROM signal.t_vessel_tracks_hourly 
                 WHERE sig_src_cd = a.sig_src_cd AND target_id = a.target_id 
                   AND time_bucket = a.last_time) as end_position
            FROM aggregated a
        """;
        
        return queryJdbcTemplate.update(sql,
            Timestamp.valueOf(dayBucket),
            Timestamp.valueOf(dayBucket),
            Timestamp.valueOf(dayBucket.plusDays(1)));
    }
    
    /**
     * Unix timestamp 검증 (수정 없이 확인만)
     */
    public MigrationController.VerifyResult verifyUnixTimestamps(String tableName, LocalDateTime timeBucket) {
        MigrationController.VerifyResult result = new MigrationController.VerifyResult();
        
        String fullTableName = getFullTableName(tableName);
        
        String sql = String.format("""
            SELECT sig_src_cd, target_id,
                   track_geom IS NOT NULL as has_v1,
                   track_geom_v2 IS NOT NULL as has_v2,
                   CASE WHEN track_geom_v2 IS NOT NULL 
                        THEN ST_M(ST_PointN(track_geom_v2, 1))::bigint 
                        ELSE NULL END as first_m,
                   EXTRACT(EPOCH FROM (start_position->>'time')::timestamp)::bigint as expected_unix,
                   CASE WHEN track_geom_v2 IS NOT NULL 
                        THEN ST_M(ST_PointN(track_geom_v2, 1))::bigint - 
                             EXTRACT(EPOCH FROM (start_position->>'time')::timestamp)::bigint
                        ELSE NULL END as diff
            FROM %s
            WHERE time_bucket = ?
            LIMIT 100
        """, fullTableName);
        
        List<Map<String, Object>> records = queryJdbcTemplate.queryForList(sql, Timestamp.valueOf(timeBucket));
        
        result.totalRecords = records.size();
        result.emptyV2Count = 0;
        result.correctV2Count = 0;
        result.incorrectV2Count = 0;
        result.samples = new Map[Math.min(5, records.size())];
        
        int sampleIdx = 0;
        for (Map<String, Object> record : records) {
            boolean hasV2 = (Boolean) record.get("has_v2");
            
            if (!hasV2) {
                result.emptyV2Count++;
            } else {
                Long diff = (Long) record.get("diff");
                if (diff != null) {
                    if (Math.abs(diff) < 30000) {
                        result.correctV2Count++;
                    } else {
                        result.incorrectV2Count++;
                    }
                }
            }
            
            // 샘플 저장
            if (sampleIdx < result.samples.length) {
                result.samples[sampleIdx++] = Map.of(
                    "sig_src_cd", record.get("sig_src_cd"),
                    "target_id", record.get("target_id"),
                    "has_v2", hasV2,
                    "first_m", record.get("first_m") != null ? record.get("first_m") : 0,
                    "expected_unix", record.get("expected_unix") != null ? record.get("expected_unix") : 0,
                    "diff", record.get("diff") != null ? record.get("diff") : 0
                );
            }
        }
        
        return result;
    }
    
    private String getFullTableName(String tableName) {
        switch (tableName.toLowerCase()) {
            case "5min":
                return "signal.t_vessel_tracks_5min";
            case "hourly":
                return "signal.t_vessel_tracks_hourly";
            case "daily":
                return "signal.t_vessel_tracks_daily";
            default:
                throw new IllegalArgumentException("Unknown table: " + tableName);
        }
    }
    
    private String getInterval(String tableName) {
        switch (tableName.toLowerCase()) {
            case "5min":
                return "5 minutes";
            case "hourly":
                return "1 hour";
            case "daily":
                return "1 day";
            default:
                return "1 hour";
        }
    }
    
    private String correctUnixTimestamp(String wkt, long adjustment) {
        String coords = wkt.replace("LINESTRING M (", "")
                          .replace("LINESTRING M(", "")
                          .replace(")", "")
                          .trim();
        String[] points = coords.split(",");
        
        StringBuilder result = new StringBuilder("LINESTRING M(");
        
        for (int i = 0; i < points.length; i++) {
            if (i > 0) result.append(",");
            String point = points[i].trim();
            String[] parts = point.split("\\s+");
            
            if (parts.length >= 3) {
                long currentM = Long.parseLong(parts[2]);
                long correctedM = currentM + adjustment;
                result.append(parts[0]).append(" ").append(parts[1]).append(" ").append(correctedM);
            }
        }
        
        result.append(")");
        return result.toString();
    }
}
