package gc.mda.signal_batch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gc.mda.signal_batch.dto.AbnormalTrackResponse;
import gc.mda.signal_batch.dto.AbnormalTrackStatsResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.sql.Timestamp;
import gc.mda.signal_batch.controller.AbnormalTrackController.TrackIdentifier;

/**
 * 비정상 궤적 서비스
 */
@Slf4j
@Service
public class AbnormalTrackService {
    
    private final JdbcTemplate jdbcTemplate;
    
    public AbnormalTrackService(@Qualifier("queryJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 특정 시간 이후의 비정상 궤적 조회
     */
    public List<AbnormalTrackResponse> getAbnormalTracksSince(LocalDateTime since) {
        String sql = """
            SELECT 
                id,
                sig_src_cd,
                target_id,
                sig_src_cd || ':' || target_id as vessel_id,
                time_bucket,
                abnormal_type,
                abnormal_reason,
                distance_nm,
                avg_speed,
                max_speed,
                point_count,
                source_table,
                detected_at,
                -- M값을 포함한 커스텀 GeoJSON 생성
                jsonb_build_object(
                    'type', 'LineString',
                    'coordinates', (
                        SELECT jsonb_agg(
                            jsonb_build_array(
                                ST_X(ST_PointN(track_geom, point_num)),
                                ST_Y(ST_PointN(track_geom, point_num)),
                                ST_M(ST_PointN(track_geom, point_num))
                            )
                        )
                        FROM generate_series(1, ST_NPoints(track_geom)) AS point_num
                    )
                )::text as track_geojson
            FROM signal.t_abnormal_tracks
            WHERE detected_at >= ?
            ORDER BY detected_at DESC
            LIMIT 1000
        """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> abnormalReason = null;
            try {
                abnormalReason = objectMapper.readValue(
                    rs.getString("abnormal_reason"), 
                    new TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception e) {
                log.error("Failed to parse abnormal_reason: {}", e.getMessage());
            }
            
            return AbnormalTrackResponse.builder()
                .id(rs.getLong("id"))
                .sigSrcCd(rs.getString("sig_src_cd"))
                .targetId(rs.getString("target_id"))
                .vesselId(rs.getString("vessel_id"))
                .timeBucket(rs.getTimestamp("time_bucket").toLocalDateTime())
                .abnormalType(rs.getString("abnormal_type"))
                .typeDescription(getTypeDescription(rs.getString("abnormal_type")))
                .abnormalDescription(getAbnormalDescription(abnormalReason))
                .distanceNm(rs.getBigDecimal("distance_nm"))
                .avgSpeed(rs.getBigDecimal("avg_speed"))
                .maxSpeed(rs.getBigDecimal("max_speed"))
                .pointCount(rs.getInt("point_count"))
                .sourceTable(rs.getString("source_table"))
                .detectedAt(rs.getTimestamp("detected_at").toLocalDateTime())
                .details(abnormalReason)
                .trackGeoJson(rs.getString("track_geojson"))
                .build();
        }, since);
    }
    
    /**
     * 특정 선박의 비정상 궤적 이력 조회
     */
    public List<AbnormalTrackResponse> getVesselAbnormalTracks(
            String sigSrcCd, String targetId, LocalDateTime startTime, LocalDateTime endTime) {
        
        String sql = """
            SELECT 
                id,
                sig_src_cd,
                target_id,
                sig_src_cd || ':' || target_id as vessel_id,
                time_bucket,
                abnormal_type,
                abnormal_reason,
                distance_nm,
                avg_speed,
                max_speed,
                point_count,
                source_table,
                detected_at
            FROM signal.t_abnormal_tracks
            WHERE sig_src_cd = ?
              AND target_id = ?
              AND time_bucket >= ?
              AND time_bucket < ?
            ORDER BY time_bucket DESC
        """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> abnormalReason = null;
            try {
                abnormalReason = objectMapper.readValue(
                    rs.getString("abnormal_reason"), 
                    new TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception e) {
                log.error("Failed to parse abnormal_reason: {}", e.getMessage());
            }
            
            return AbnormalTrackResponse.builder()
                .id(rs.getLong("id"))
                .sigSrcCd(rs.getString("sig_src_cd"))
                .targetId(rs.getString("target_id"))
                .vesselId(rs.getString("vessel_id"))
                .timeBucket(rs.getTimestamp("time_bucket").toLocalDateTime())
                .abnormalType(rs.getString("abnormal_type"))
                .typeDescription(getTypeDescription(rs.getString("abnormal_type")))
                .abnormalDescription(getAbnormalDescription(abnormalReason))
                .distanceNm(rs.getBigDecimal("distance_nm"))
                .avgSpeed(rs.getBigDecimal("avg_speed"))
                .maxSpeed(rs.getBigDecimal("max_speed"))
                .pointCount(rs.getInt("point_count"))
                .sourceTable(rs.getString("source_table"))
                .detectedAt(rs.getTimestamp("detected_at").toLocalDateTime())
                .details(abnormalReason)
                .build();
        }, sigSrcCd, targetId, startTime, endTime);
    }
    
    /**
     * 비정상 궤적 통계 조회
     */
    public List<AbnormalTrackStatsResponse> getStatistics(LocalDate startDate, LocalDate endDate) {
        String sql = """
            SELECT 
                stat_date,
                abnormal_type,
                vessel_count,
                track_count,
                total_points,
                avg_deviation,
                max_deviation
            FROM signal.t_abnormal_track_stats
            WHERE stat_date >= ?
              AND stat_date <= ?
            ORDER BY stat_date DESC, track_count DESC
        """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> 
            AbnormalTrackStatsResponse.builder()
                .statDate(rs.getDate("stat_date").toLocalDate())
                .abnormalType(rs.getString("abnormal_type"))
                .vesselCount(rs.getInt("vessel_count"))
                .trackCount(rs.getInt("track_count"))
                .totalPoints(rs.getInt("total_points"))
                .avgDeviation(rs.getBigDecimal("avg_deviation"))
                .maxDeviation(rs.getBigDecimal("max_deviation"))
                .build(),
            startDate, endDate
        );
    }
    
    /**
     * 요약 통계 조회
     */
    public Map<String, Object> getSummaryStatistics(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        
        // 전체 통계
        String totalSql = """
            SELECT 
                COUNT(DISTINCT abnormal_type) as type_count,
                COUNT(*) as total_tracks,
                COUNT(DISTINCT sig_src_cd || ':' || target_id) as vessel_count,
                AVG(distance_nm) as avg_distance,
                MAX(max_speed) as max_speed_detected
            FROM signal.t_abnormal_tracks
            WHERE detected_at >= ?
        """;
        
        Map<String, Object> summary = jdbcTemplate.queryForMap(totalSql, since);
        
        // 유형별 통계
        String typeSql = """
            SELECT 
                abnormal_type,
                COUNT(*) as count,
                COUNT(DISTINCT sig_src_cd || ':' || target_id) as vessel_count
            FROM signal.t_abnormal_tracks
            WHERE detected_at >= ?
            GROUP BY abnormal_type
            ORDER BY count DESC
        """;
        
        List<Map<String, Object>> typeStats = jdbcTemplate.queryForList(typeSql, since);
        summary.put("typeStatistics", typeStats);
        
        // 일별 추이
        String trendSql = """
            SELECT 
                DATE(detected_at) as date,
                COUNT(*) as count
            FROM signal.t_abnormal_tracks
            WHERE detected_at >= ?
            GROUP BY DATE(detected_at)
            ORDER BY date
        """;
        
        List<Map<String, Object>> trend = jdbcTemplate.queryForList(trendSql, since);
        summary.put("dailyTrend", trend);
        
        return summary;
    }
    
    /**
     * 오래된 데이터 정리
     */
    /**
     * hourly/daily 테이블에서 사용자 정의 기준으로 비정상 궤적 검출
     */
    public List<AbnormalTrackResponse> detectFromHourlyDaily(
            String tableType, 
            LocalDateTime startTime, 
            LocalDateTime endTime,
            BigDecimal minDistance,
            BigDecimal minSpeed) {
        
        // null 처리 - final 변수로 생성
        final BigDecimal finalMinDistance = minDistance == null ? BigDecimal.ZERO : minDistance;
        final BigDecimal finalMinSpeed = minSpeed == null ? BigDecimal.ZERO : minSpeed;
        
        String tableName = tableType.equals("hourly") ? 
            "t_vessel_tracks_hourly" : "t_vessel_tracks_daily";
        
        String sql = String.format("""
            SELECT 
                sig_src_cd,
                target_id,
                sig_src_cd || ':' || target_id as vessel_id,
                time_bucket,
                distance_nm,
                avg_speed,
                max_speed,
                point_count,
                -- M값을 포함한 커스텀 GeoJSON 생성
                jsonb_build_object(
                    'type', 'LineString',
                    'coordinates', (
                        SELECT jsonb_agg(
                            jsonb_build_array(
                                ST_X(ST_PointN(track_geom, point_num)),
                                ST_Y(ST_PointN(track_geom, point_num)),
                                ST_M(ST_PointN(track_geom, point_num))
                            )
                        )
                        FROM generate_series(1, ST_NPoints(track_geom)) AS point_num
                    )
                )::text as track_geojson,
                start_position,
                end_position
            FROM signal.%s
            WHERE time_bucket >= ?
              AND time_bucket < ?
              AND (? = 0 OR distance_nm >= ?)
              AND (? = 0 OR avg_speed >= ?)
            ORDER BY time_bucket DESC, distance_nm DESC
            LIMIT 500
        """, tableName);
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            // 비정상 유형 결정
            String abnormalType = "user_detected";
            String description = "사용자 정의 기준 (거리: " + finalMinDistance + "nm, 속도: " + finalMinSpeed + "kts 이상)";
            
            BigDecimal distance = rs.getBigDecimal("distance_nm");
            BigDecimal avgSpeed = rs.getBigDecimal("avg_speed");
            
            if (avgSpeed != null && avgSpeed.compareTo(new BigDecimal("100")) > 0) {
                abnormalType = "extreme_speed";
                description = "극단적 속도: " + avgSpeed + "kts";
            } else if (distance != null && distance.compareTo(new BigDecimal("50")) > 0) {
                abnormalType = "extreme_distance";
                description = "극단적 이동거리: " + distance + "nm";
            }
            
            String sigSrcCd = rs.getString("sig_src_cd");
            String targetId = rs.getString("target_id");
            LocalDateTime timeBucket = rs.getTimestamp("time_bucket").toLocalDateTime();
            
            // ID를 조합 해시로 생성 (고유성 보장)
            long generatedId = (sigSrcCd + targetId + timeBucket.toString()).hashCode() & 0x7fffffffL;
            
            return AbnormalTrackResponse.builder()
                .id(generatedId)
                .sigSrcCd(sigSrcCd)
                .targetId(targetId)
                .vesselId(rs.getString("vessel_id"))
                .timeBucket(timeBucket)
                .abnormalType(abnormalType)
                .typeDescription(getTypeDescription(abnormalType))
                .abnormalDescription(description)
                .distanceNm(distance)
                .avgSpeed(avgSpeed)
                .maxSpeed(rs.getBigDecimal("max_speed"))
                .pointCount(rs.getInt("point_count"))
                .sourceTable(tableName)
                .detectedAt(LocalDateTime.now())
                .details(Map.of(
                    "tableType", tableType,
                    "minDistance", finalMinDistance,
                    "minSpeed", finalMinSpeed
                ))
                .trackGeoJson(rs.getString("track_geojson"))
                .build();
        }, startTime, endTime, finalMinDistance, finalMinDistance, finalMinSpeed, finalMinSpeed);
    }
    
    /**
     * 선택된 궤적을 비정상 테이블로 이동 (정확한 버전)
     */
    @Transactional
    public int moveToAbnormalTracks(
            String tableType,
            List<TrackIdentifier> tracks,
            String abnormalType,
            String reason) {
        
        if (tracks == null || tracks.isEmpty()) {
            return 0;
        }
        
        String sourceTable = tableType.equals("hourly") ? 
            "t_vessel_tracks_hourly" : "t_vessel_tracks_daily";
        
        String reasonJson = "{\"type\": \"" + abnormalType + "\", \"reason\": \"" + reason + "\"}";
        int totalMoved = 0;
        
        // 각 트랙을 개별적으로 처리 (배치 처리도 가능하지만 정확성을 위해)
        for (TrackIdentifier track : tracks) {
            try {
                // 1. t_abnormal_tracks로 복사
                String insertSql = String.format("""
                    INSERT INTO signal.t_abnormal_tracks (
                        sig_src_cd, target_id, time_bucket, abnormal_type, abnormal_reason,
                        distance_nm, avg_speed, max_speed, point_count, track_geom,
                        source_table, detected_at
                    )
                    SELECT 
                        sig_src_cd, target_id, time_bucket, ?, ?::jsonb,
                        distance_nm, avg_speed, max_speed, point_count, track_geom,
                        ?, NOW()
                    FROM signal.%s
                    WHERE sig_src_cd = ?
                      AND target_id = ?
                      AND time_bucket = ?
                """, sourceTable);
                
                int inserted = jdbcTemplate.update(insertSql, 
                    abnormalType,
                    reasonJson,
                    sourceTable,
                    track.getSigSrcCd(),
                    track.getTargetId(),
                    track.getTimeBucket()
                );
                
                if (inserted > 0) {
                    // 2. 원본 테이블에서 삭제
                    String deleteSql = String.format("""
                        DELETE FROM signal.%s
                        WHERE sig_src_cd = ?
                          AND target_id = ?
                          AND time_bucket = ?
                    """, sourceTable);
                    
                    int deleted = jdbcTemplate.update(deleteSql,
                        track.getSigSrcCd(),
                        track.getTargetId(),
                        track.getTimeBucket()
                    );
                    
                    if (deleted > 0) {
                        totalMoved++;
                        log.debug("Moved track: {} {} {}", 
                            track.getSigSrcCd(), track.getTargetId(), track.getTimeBucket());
                    } else {
                        log.warn("Failed to delete track after insert: {} {} {}",
                            track.getSigSrcCd(), track.getTargetId(), track.getTimeBucket());
                    }
                }
            } catch (Exception e) {
                log.error("Error moving track {} {} {}: {}", 
                    track.getSigSrcCd(), track.getTargetId(), track.getTimeBucket(), e.getMessage());
            }
        }
        
        log.info("Successfully moved {} tracks from {} to t_abnormal_tracks", totalMoved, sourceTable);
        
        return totalMoved;
    }
    
    @Transactional
    public int cleanupOldData(int retentionDays) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        
        String sql = "DELETE FROM signal.t_abnormal_tracks WHERE detected_at < ?";
        int deletedCount = jdbcTemplate.update(sql, cutoffDate);
        
        log.info("Cleaned up {} abnormal tracks older than {} days", deletedCount, retentionDays);
        
        return deletedCount;
    }
    
    private String getTypeDescription(String type) {
        return switch (type) {
            case "excessive_speed" -> "과속";
            case "teleport" -> "순간이동";
            case "gap_jump" -> "Bucket 간 점프";
            case "excessive_acceleration" -> "급가속";
            case "extreme_avg_speed_5min" -> "순간이동"; // 5분 극단 속도를 순간이동으로 분류
            case "user_detected" -> "사용자 검출";
            default -> type;
        };
    }
    
    /**
     * 비정상 궤적 설명 추출 (null 안전하게)
     */
    private String getAbnormalDescription(Map<String, Object> abnormalReason) {
        if (abnormalReason == null) {
            return "";
        }
        
        try {
            Object segments = abnormalReason.get("segments");
            if (segments instanceof List) {
                List<Map<String, Object>> segmentList = (List<Map<String, Object>>) segments;
                if (!segmentList.isEmpty()) {
                    Map<String, Object> firstSegment = segmentList.get(0);
                    if (firstSegment != null) {
                        Object description = firstSegment.get("description");
                        return description != null ? description.toString() : "";
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract abnormal description: {}", e.getMessage());
        }
        
        return "";
    }
}
