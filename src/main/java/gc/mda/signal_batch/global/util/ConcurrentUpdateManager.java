package gc.mda.signal_batch.global.util;

import gc.mda.signal_batch.domain.vessel.model.VesselLatestPosition;
import gc.mda.signal_batch.domain.gis.model.TileStatistics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrentUpdateManager {

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    @Value("${vessel.batch.lock.timeout:10}")
    private int lockTimeoutSeconds;

    @Value("${vessel.batch.lock.max-retry:3}")
    private int maxRetryAttempts;

    // 락 통계 관리
    private final Map<String, LockStatistics> lockStats = new ConcurrentHashMap<>();

    /**
     * Advisory Lock을 사용한 최신 위치 업데이트
     */
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, maxDelay = 1000, multiplier = 2)
    )
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public int updateLatestPositionWithLock(VesselLatestPosition position) {
        String lockKey = position.getSigSrcCd() + ":" + position.getTargetId();
        long lockId = generateLockId(lockKey);

        LocalDateTime startTime = LocalDateTime.now();
        LockStatistics stats = lockStats.computeIfAbsent(lockKey, k -> new LockStatistics());
        stats.attempts.incrementAndGet();

        try {
            // Advisory Lock 획득 시도
            Boolean lockAcquired = queryJdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)",
                    Boolean.class,
                    lockId
            );

            if (!lockAcquired) {
                stats.failures.incrementAndGet();
                log.debug("Failed to acquire lock for vessel: {}", lockKey);

                // 대기 후 재시도
                Thread.sleep(50);
                return updateLatestPositionWithoutLock(position);
            }

            // 락 획득 성공 - 업데이트 수행
            // 방법 1: queryForList 사용 (권장)
            String sql = """
            INSERT INTO signal.t_vessel_latest_position (
                sig_src_cd, target_id, lat, lon, geom,
                sog, cog, heading, ship_nm, ship_ty,
                last_update, update_count, created_at
            ) VALUES (
                ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326),
                ?, ?, ?, ?, ?,
                ?, 1, CURRENT_TIMESTAMP
            )
            ON CONFLICT (sig_src_cd, target_id) DO UPDATE SET
                lat = EXCLUDED.lat,
                lon = EXCLUDED.lon,
                geom = EXCLUDED.geom,
                sog = EXCLUDED.sog,
                cog = EXCLUDED.cog,
                heading = EXCLUDED.heading,
                ship_nm = COALESCE(EXCLUDED.ship_nm, t_vessel_latest_position.ship_nm),
                ship_ty = COALESCE(EXCLUDED.ship_ty, t_vessel_latest_position.ship_ty),
                last_update = EXCLUDED.last_update,
                update_count = t_vessel_latest_position.update_count + 1
            WHERE EXCLUDED.last_update > t_vessel_latest_position.last_update
            RETURNING update_count
        """;

            List<Integer> results = queryJdbcTemplate.queryForList(sql,
                    new Object[]{
                            position.getSigSrcCd(),
                            position.getTargetId(),
                            position.getLat(),
                            position.getLon(),
                            position.getLon(),
                            position.getLat(),
                            position.getSog(),
                            position.getCog(),
                            position.getHeading(),
                            position.getShipNm(),
                            position.getShipTy(),
                            Timestamp.valueOf(position.getLastUpdate())
                    },
                    Integer.class
            );

            // 결과 확인 - 빈 리스트면 업데이트 안됨 (이미 최신 데이터)
            int updateResult = results.isEmpty() ? 0 : 1;

            if (updateResult == 0) {
                log.debug("Skipped update for vessel {} - existing data is newer", lockKey);
            }

            stats.successes.incrementAndGet();
            Duration duration = Duration.between(startTime, LocalDateTime.now());
            stats.totalDuration.addAndGet((int) duration.toMillis());

            return updateResult;

        } catch (Exception e) {
            stats.errors.incrementAndGet();
            log.error("Error updating vessel position: {}", lockKey, e);
            throw new RuntimeException("Failed to update vessel position", e);

        } finally {
            // Advisory Lock 해제
            try {
                queryJdbcTemplate.update("SELECT pg_advisory_unlock(?)", lockId);
            } catch (Exception e) {
                log.warn("Failed to release advisory lock: {}", lockId);
            }
        }
    }

    /**
     * 락 없이 업데이트 (Fallback) - 수정 버전
     */
    private int updateLatestPositionWithoutLock(VesselLatestPosition position) {
        String sql = """
        INSERT INTO signal.t_vessel_latest_position (
            sig_src_cd, target_id, lat, lon, geom,
            sog, cog, heading, ship_nm, ship_ty,
            last_update, update_count, created_at
        ) VALUES (
            ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326),
            ?, ?, ?, ?, ?,
            ?, 1, CURRENT_TIMESTAMP
        )
        ON CONFLICT (sig_src_cd, target_id) DO UPDATE SET
            lat = EXCLUDED.lat,
            lon = EXCLUDED.lon,
            geom = EXCLUDED.geom,
            sog = EXCLUDED.sog,
            cog = EXCLUDED.cog,
            heading = EXCLUDED.heading,
            ship_nm = COALESCE(EXCLUDED.ship_nm, t_vessel_latest_position.ship_nm),
            ship_ty = COALESCE(EXCLUDED.ship_ty, t_vessel_latest_position.ship_ty),
            last_update = EXCLUDED.last_update,
            update_count = t_vessel_latest_position.update_count + 1
        WHERE EXCLUDED.last_update > t_vessel_latest_position.last_update
    """;

        return queryJdbcTemplate.update(sql,
                position.getSigSrcCd(),
                position.getTargetId(),
                position.getLat(),
                position.getLon(),
                position.getLon(),
                position.getLat(),
                position.getSog(),
                position.getCog(),
                position.getHeading(),
                position.getShipNm(),
                position.getShipTy(),
                Timestamp.valueOf(position.getLastUpdate())
        );
    }
    /**
     * 배치 업데이트 with Row-Level Locking
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void batchUpdateWithRowLock(List<VesselLatestPosition> positions) {
        // 선박별로 정렬하여 데드락 방지
        positions.sort(Comparator.comparing(p -> p.getSigSrcCd() + p.getTargetId()));

        String lockSql = """
            SELECT 1 FROM signal.t_vessel_latest_position
            WHERE sig_src_cd = ? AND target_id = ?
            FOR UPDATE NOWAIT
        """;

        String updateSql = """
            UPDATE signal.t_vessel_latest_position SET
                lat = ?, lon = ?, geom = ST_SetSRID(ST_MakePoint(?, ?), 4326),
                sog = ?, cog = ?, heading = ?,
                ship_nm = COALESCE(?, ship_nm),
                ship_ty = COALESCE(?, ship_ty),
                last_update = ?,
                update_count = update_count + 1
            WHERE sig_src_cd = ? AND target_id = ?
              AND ? > last_update
        """;

        String insertSql = """
            INSERT INTO signal.t_vessel_latest_position (
                sig_src_cd, target_id, lat, lon, geom,
                sog, cog, heading, ship_nm, ship_ty,
                last_update, update_count, created_at
            ) VALUES (
                ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326),
                ?, ?, ?, ?, ?,
                ?, 1, CURRENT_TIMESTAMP
            )
        """;

        for (VesselLatestPosition position : positions) {
            try {
                // Row lock 시도
                List<Integer> locked = queryJdbcTemplate.queryForList(
                        lockSql, Integer.class,
                        position.getSigSrcCd(), position.getTargetId()
                );

                if (!locked.isEmpty()) {
                    // 업데이트
                    int updated = queryJdbcTemplate.update(updateSql,
                            position.getLat(), position.getLon(),
                            position.getLon(), position.getLat(),
                            position.getSog(), position.getCog(), position.getHeading(),
                            position.getShipNm(), position.getShipTy(),
                            Timestamp.valueOf(position.getLastUpdate()),
                            position.getSigSrcCd(), position.getTargetId(),
                            Timestamp.valueOf(position.getLastUpdate())
                    );

                    if (updated == 0) {
                        log.debug("Skipped outdated update for vessel: {}:{}",
                                position.getSigSrcCd(), position.getTargetId());
                    }
                } else {
                    // 신규 삽입
                    queryJdbcTemplate.update(insertSql,
                            position.getSigSrcCd(), position.getTargetId(),
                            position.getLat(), position.getLon(),
                            position.getLon(), position.getLat(),
                            position.getSog(), position.getCog(), position.getHeading(),
                            position.getShipNm(), position.getShipTy(),
                            Timestamp.valueOf(position.getLastUpdate())
                    );
                }

            } catch (Exception e) {
                log.warn("Failed to update vessel position: {}:{}",
                        position.getSigSrcCd(), position.getTargetId(), e);
            }
        }
    }

    /**
     * 타일 통계 병합 업데이트
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void mergeTileStatistics(List<TileStatistics> statistics) {
        String sql = """
            INSERT INTO signal.t_tile_summary (
                tile_id, tile_level, time_bucket, vessel_count,
                unique_vessels, total_points, avg_sog, max_sog,
                vessel_density, created_at
            ) VALUES (
                ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?
            )
            ON CONFLICT (tile_id, time_bucket) DO UPDATE SET
                vessel_count = t_tile_summary.vessel_count + EXCLUDED.vessel_count,
                unique_vessels = t_tile_summary.unique_vessels || EXCLUDED.unique_vessels,
                total_points = t_tile_summary.total_points + EXCLUDED.total_points,
                avg_sog = (t_tile_summary.avg_sog * t_tile_summary.total_points + 
                          EXCLUDED.avg_sog * EXCLUDED.total_points) / 
                         (t_tile_summary.total_points + EXCLUDED.total_points),
                max_sog = GREATEST(t_tile_summary.max_sog, EXCLUDED.max_sog),
                vessel_density = (t_tile_summary.vessel_count + EXCLUDED.vessel_count) / 
                               (SELECT area FROM signal.t_grid_tiles WHERE tile_id = t_tile_summary.tile_id)
        """;

        queryJdbcTemplate.batchUpdate(sql, statistics, 100, (ps, stat) -> {
            ps.setString(1, stat.getTileId());
            ps.setInt(2, stat.getTileLevel());
            ps.setTimestamp(3, java.sql.Timestamp.valueOf(stat.getTimeBucket()));
            ps.setInt(4, stat.getVesselCount());
            ps.setString(5, convertToJson(stat.getUniqueVessels()));
            ps.setLong(6, stat.getTotalPoints());
            ps.setBigDecimal(7, stat.getAvgSog());
            ps.setBigDecimal(8, stat.getMaxSog());
            ps.setBigDecimal(9, stat.getVesselDensity());
            ps.setTimestamp(10, java.sql.Timestamp.valueOf(LocalDateTime.now()));
        });
    }

    /**
     * 락 ID 생성
     */
    private long generateLockId(String key) {
        // PostgreSQL advisory lock은 bigint 사용
        return Math.abs(key.hashCode());
    }

    /**
     * JSON 변환
     */
    private String convertToJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 락 통계 조회
     */
    public Map<String, Map<String, Object>> getLockStatistics() {
        Map<String, Map<String, Object>> result = new HashMap<>();

        lockStats.forEach((key, stats) -> {
            Map<String, Object> statMap = new HashMap<>();
            statMap.put("attempts", stats.attempts.get());
            statMap.put("successes", stats.successes.get());
            statMap.put("failures", stats.failures.get());
            statMap.put("errors", stats.errors.get());

            if (stats.attempts.get() > 0) {
                statMap.put("successRate",
                        (double) stats.successes.get() / stats.attempts.get());
                statMap.put("avgDurationMs",
                        stats.totalDuration.get() / stats.successes.get());
            }

            result.put(key, statMap);
        });

        return result;
    }

    /**
     * 현재 락 상태 모니터링
     */
    public List<Map<String, Object>> getCurrentLocks() {
        String sql = """
            SELECT 
                pid,
                locktype,
                database,
                relation::regclass,
                mode,
                granted,
                EXTRACT(EPOCH FROM (NOW() - query_start)) as duration_seconds
            FROM pg_locks l
            JOIN pg_stat_activity a ON l.pid = a.pid
            WHERE l.locktype IN ('advisory', 'relation', 'tuple')
              AND a.application_name LIKE '%vessel-batch%'
            ORDER BY duration_seconds DESC
        """;

        return queryJdbcTemplate.queryForList(sql);
    }

    /**
     * 데드락 모니터링
     */
    public List<Map<String, Object>> getDeadlockInfo() {
        String sql = """
            SELECT 
                pid,
                usename,
                application_name,
                client_addr,
                query_start,
                state,
                wait_event_type,
                wait_event,
                query
            FROM pg_stat_activity
            WHERE wait_event_type = 'Lock'
              AND state != 'idle'
            ORDER BY query_start
        """;

        return queryJdbcTemplate.queryForList(sql);
    }

    /**
     * 락 통계 클래스
     */
    private static class LockStatistics {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger totalDuration = new AtomicInteger();
    }
}