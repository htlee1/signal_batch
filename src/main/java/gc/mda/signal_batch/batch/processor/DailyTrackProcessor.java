package gc.mda.signal_batch.batch.processor;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import gc.mda.signal_batch.global.util.LineStringMUtils;
import gc.mda.signal_batch.global.util.TrackSimplificationUtils;

import javax.sql.DataSource;


@Slf4j
@RequiredArgsConstructor
public class DailyTrackProcessor implements ItemProcessor<VesselTrack.VesselKey, VesselTrack> {

    private final DataSource queryDataSource;
    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public VesselTrack process(VesselTrack.VesselKey vesselKey) throws Exception {
        LocalDateTime dayBucket = vesselKey.getTimeBucket()
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        // track_geom만 사용 (Unix timestamp 병합 쿼리)
        String sql = """
            WITH ordered_tracks AS (
                SELECT *
                FROM signal.t_vessel_tracks_hourly
                WHERE sig_src_cd = ?
                    AND target_id = ?
                    AND time_bucket >= ?
                    AND time_bucket < ?
                    AND track_geom IS NOT NULL
                ORDER BY time_bucket
            ),
            track_points AS (
                SELECT 
                    o.sig_src_cd,
                    o.target_id,
                    o.time_bucket,
                    (ST_DumpPoints(o.track_geom)).geom as point,
                    (ST_DumpPoints(o.track_geom)).path[1] as point_order
                FROM ordered_tracks o
            ),
            merged_tracks AS (
                SELECT 
                    sig_src_cd,
                    target_id,
                    ?::timestamp as time_bucket,
                    -- Unix timestamp 그대로 사용 (재계산 불필요)
                    CASE 
                        WHEN COUNT(point) = 1 THEN
                            ST_GeomFromText(
                                'LINESTRING M(' || 
                                ST_X(MIN(point)) || ' ' || ST_Y(MIN(point)) || ' ' || ST_M(MIN(point)) || ',' ||
                                ST_X(MIN(point)) || ' ' || ST_Y(MIN(point)) || ' ' || ST_M(MIN(point)) || ')'
                            )
                        ELSE
                            ST_MakeLine(point ORDER BY time_bucket, point_order)
                    END as merged_geom,
                    MAX(max_speed) as max_speed,
                    SUM(point_count) as total_points,
                    MIN(time_bucket) as start_time,
                    MAX(time_bucket) as end_time,
                    (SELECT start_position FROM ordered_tracks ORDER BY time_bucket LIMIT 1) as start_pos,
                    (SELECT end_position FROM ordered_tracks ORDER BY time_bucket DESC LIMIT 1) as end_pos
                FROM ordered_tracks
                JOIN track_points USING (sig_src_cd, target_id, time_bucket)
                GROUP BY sig_src_cd, target_id
            ),
            calculated_tracks AS (
                SELECT 
                    *,
                    -- ST_Length를 사용하여 실제 거리 계산 (미터 -> 해리 변환: / 1852)
                    ST_Length(merged_geom::geography) / 1852.0 as total_distance,
                    -- Unix timestamp 기반 시간 차이 계산
                    CASE
                        WHEN ST_NPoints(merged_geom) > 0 THEN
                            -- Unix timestamp: 마지막 M - 첫 M
                            ST_M(ST_PointN(merged_geom, ST_NPoints(merged_geom))) - 
                            ST_M(ST_PointN(merged_geom, 1))
                        ELSE
                            EXTRACT(EPOCH FROM 
                                (end_pos->>'time')::timestamp - (start_pos->>'time')::timestamp
                            )
                    END as time_diff_seconds
                FROM merged_tracks
            )
            SELECT 
                sig_src_cd,
                target_id,
                time_bucket,
                merged_geom,
                total_distance,
                -- 평균 속도 계산: 거리(해리) / 시간(시간) = 속도(노트)
                CASE 
                    WHEN time_diff_seconds > 0 THEN 
                        LEAST((total_distance / (time_diff_seconds / 3600.0)), 9999.99)::numeric(6,2)
                    ELSE 0
                END as avg_speed,
                max_speed,
                total_points,
                start_time,
                end_time,
                start_pos,
                end_pos,
                ST_AsText(merged_geom) as geom_text 
            FROM calculated_tracks
        """;

        LocalDateTime startTime = dayBucket;
        LocalDateTime endTime = dayBucket.plusDays(1);

        try {
            return jdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> {
                        try {
                            return buildDailyTrack(rs, dayBucket);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to build daily track", e);
                        }
                    },
                    vesselKey.getSigSrcCd(), vesselKey.getTargetId(),
                    startTime, endTime, dayBucket
            );
        } catch (Exception e) {
            log.error("Failed to process daily track for vessel {}: {}",
                    vesselKey.getSigSrcCd() + "_" + vesselKey.getTargetId(), e.getMessage());
            return null;
        }
    }

    private VesselTrack buildDailyTrack(ResultSet rs, LocalDateTime dayBucket) throws Exception {
        // Start/End position 추출
        VesselTrack.TrackPosition startPos = null;
        VesselTrack.TrackPosition endPos = null;

        String startPosJson = rs.getString("start_pos");
        String endPosJson = rs.getString("end_pos");

        if (startPosJson != null) {
            startPos = parseTrackPosition(startPosJson);
        }

        if (endPosJson != null) {
            endPos = parseTrackPosition(endPosJson);
        }

        // M값은 이미 SQL에서 재계산됨
        String dailyLineStringM = rs.getString("geom_text");

        // 일별 궤적 간소화 (20m 이내 생략, 최대 30분 간격)
        String simplifiedLineStringM = TrackSimplificationUtils.simplifyDailyTrack(dailyLineStringM);

        // 간소화 통계 로깅
        if (!dailyLineStringM.equals(simplifiedLineStringM)) {
            TrackSimplificationUtils.SimplificationStats stats =
                    TrackSimplificationUtils.getSimplificationStats(dailyLineStringM, simplifiedLineStringM);
            log.debug("일별 궤적 간소화 - vessel: {}/{}, 원본: {}포인트, 간소화: {}포인트 ({}% 감소)",
                    rs.getString("sig_src_cd"), rs.getString("target_id"),
                    stats.originalPoints, stats.simplifiedPoints, (int)stats.reductionRate);
        }

        // track_geom만 사용
        return VesselTrack.builder()
                .sigSrcCd(rs.getString("sig_src_cd"))
                .targetId(rs.getString("target_id"))
                .timeBucket(dayBucket)
                .trackGeom(simplifiedLineStringM)
                .distanceNm(rs.getBigDecimal("total_distance"))
                .avgSpeed(rs.getBigDecimal("avg_speed"))
                .maxSpeed(rs.getBigDecimal("max_speed"))
                .pointCount(rs.getInt("total_points"))
                .startPosition(startPos)
                .endPosition(endPos)
                .build();
    }

    private VesselTrack.TrackPosition parseTrackPosition(String json) {
        try {
            String latStr = LineStringMUtils.extractJsonValue(json, "lat");
            String lonStr = LineStringMUtils.extractJsonValue(json, "lon");
            String timeStr = LineStringMUtils.extractJsonValue(json, "time");
            String sogStr = LineStringMUtils.extractJsonValue(json, "sog");

            return VesselTrack.TrackPosition.builder()
                    .lat(latStr != null ? Double.parseDouble(latStr) : null)
                    .lon(lonStr != null ? Double.parseDouble(lonStr) : null)
                    .time(timeStr != null ? LocalDateTime.parse(timeStr, TIMESTAMP_FORMATTER) : null)
                    .sog(sogStr != null ? new BigDecimal(sogStr) : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse track position: {}", json, e);
            return null;
        }
    }
}