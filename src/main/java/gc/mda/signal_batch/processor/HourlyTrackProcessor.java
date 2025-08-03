package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.model.VesselTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import gc.mda.signal_batch.util.LineStringMUtils;
import gc.mda.signal_batch.util.TrackSimplificationUtils;

@Slf4j
@RequiredArgsConstructor
public class HourlyTrackProcessor implements ItemProcessor<VesselTrack.VesselKey, VesselTrack> {
    
    private final DataSource queryDataSource;
    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public VesselTrack process(VesselTrack.VesselKey vesselKey) throws Exception {
        LocalDateTime hourBucket = vesselKey.getTimeBucket()
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        
        // 5분 데이터 병합 쿼리
        String sql = """
            WITH ordered_tracks AS (
                SELECT *
                FROM signal.t_vessel_tracks_5min
                WHERE sig_src_cd = ?
                    AND target_id = ?
                    AND time_bucket >= ?
                    AND time_bucket < ?
                ORDER BY time_bucket
            ),
            first_track_time AS (
                SELECT 
                    (start_position->>'time')::timestamp as first_time
                FROM ordered_tracks
                ORDER BY time_bucket
                LIMIT 1
            ),
            track_points AS (
                SELECT 
                    o.sig_src_cd,
                    o.target_id,
                    o.time_bucket,
                    (ST_DumpPoints(o.track_geom)).geom as point,
                    (ST_DumpPoints(o.track_geom)).path[1] as point_order,
                    ST_M((ST_DumpPoints(o.track_geom)).geom) as original_m,
                    (o.start_position->>'time')::timestamp as track_start_time
                FROM ordered_tracks o
            ),
            merged_tracks AS (
                SELECT 
                    sig_src_cd,
                    target_id,
                    ?::timestamp as time_bucket,
                    -- Ensure we always get a LineString, not a Point
                    CASE 
                        WHEN COUNT(point) = 1 THEN
                            -- For single point, duplicate it to create valid LineString
                            ST_GeomFromText(
                                'LINESTRING M(' || 
                                ST_X(MIN(point)) || ' ' || ST_Y(MIN(point)) || ' ' || 
                                (EXTRACT(EPOCH FROM MIN(track_start_time) - (SELECT first_time FROM first_track_time)) + MIN(original_m)) || ',' ||
                                ST_X(MIN(point)) || ' ' || ST_Y(MIN(point)) || ' ' || 
                                (EXTRACT(EPOCH FROM MIN(track_start_time) - (SELECT first_time FROM first_track_time)) + MIN(original_m)) || ')'
                            )
                        ELSE
                            ST_MakeLine(
                                ST_MakePointM(
                                    ST_X(point), 
                                    ST_Y(point), 
                                    EXTRACT(EPOCH FROM track_start_time - (SELECT first_time FROM first_track_time)) + original_m
                                ) ORDER BY time_bucket, point_order
                            )
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
                    -- 시간 차이 계산을 위한 JSON 파싱
                    EXTRACT(EPOCH FROM 
                        (end_pos->>'time')::timestamp - (start_pos->>'time')::timestamp
                    ) as time_diff_seconds
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
        
        LocalDateTime startTime = hourBucket;
        LocalDateTime endTime = hourBucket.plusHours(1);
        
        try {
            return jdbcTemplate.queryForObject(sql, 
                (rs, rowNum) -> {
                    try {
                        return buildHourlyTrack(rs, hourBucket);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to build hourly track", e);
                    }
                },
                vesselKey.getSigSrcCd(), vesselKey.getTargetId(), 
                startTime, endTime, hourBucket
            );
        } catch (Exception e) {
            log.error("Failed to process hourly track for vessel {}: {}", 
                vesselKey.getSigSrcCd() + "_" + vesselKey.getTargetId(), e.getMessage());
            return null;
        }
    }
    
    private VesselTrack buildHourlyTrack(ResultSet rs, LocalDateTime hourBucket) throws Exception {
        // Start/End position 추출
        VesselTrack.TrackPosition startPos = null;
        VesselTrack.TrackPosition endPos = null;
        
        String startPosJson = rs.getString("start_pos");
        String endPosJson = rs.getString("end_pos");
        
        if (startPosJson != null) {
            // JSON 파싱 (간단한 구조 가정)
            startPos = parseTrackPosition(startPosJson);
        }
        
        if (endPosJson != null) {
            endPos = parseTrackPosition(endPosJson);
        }
        
        // M값은 이미 SQL에서 재계산됨
        String hourlyLineStringM = rs.getString("geom_text");
        
        // 이동이 거의 없는 포인트 간소화 (10m 이내 생략, 최대 10분 간격)
        String simplifiedLineStringM = TrackSimplificationUtils.simplifyHourlyTrack(hourlyLineStringM);
        
        // 간소화 통계 로깅
        if (!hourlyLineStringM.equals(simplifiedLineStringM)) {
            TrackSimplificationUtils.SimplificationStats stats = 
                TrackSimplificationUtils.getSimplificationStats(hourlyLineStringM, simplifiedLineStringM);
            log.debug("시간별 궤적 간소화 - vessel: {}/{}, 원본: {}포인트, 간소화: {}포인트 ({}% 감소)",
                rs.getString("sig_src_cd"), rs.getString("target_id"),
                stats.originalPoints, stats.simplifiedPoints, (int)stats.reductionRate);
        }
        
        return VesselTrack.builder()
                .sigSrcCd(rs.getString("sig_src_cd"))
                .targetId(rs.getString("target_id"))
                .timeBucket(hourBucket)
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
