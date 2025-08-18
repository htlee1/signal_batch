package gc.mda.signal_batch.service;

import gc.mda.signal_batch.dto.GisBoundaryResponse;
import gc.mda.signal_batch.dto.TrackResponse;
import gc.mda.signal_batch.dto.VesselStatsResponse;
import gc.mda.signal_batch.dto.VesselTracksRequest;
import gc.mda.signal_batch.dto.CompactVesselTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.locationtech.jts.io.WKTReader;

@Slf4j
@Service
@RequiredArgsConstructor
public class GisService {
    
    private final DataSource queryDataSource;
    
    public List<GisBoundaryResponse> getHaeguBoundaries() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT haegu_no, center_lat, center_lon,
                   ST_AsGeoJSON(geom) as geom_json
            FROM signal.t_haegu_definitions
            ORDER BY haegu_no
        """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> 
            GisBoundaryResponse.builder()
                .haeguNo(rs.getInt("haegu_no"))
                .centerLat(rs.getDouble("center_lat"))
                .centerLon(rs.getDouble("center_lon"))
                .geomJson(rs.getString("geom_json"))
                .build()
        );
    }
    
    public Map<Integer, VesselStatsResponse> getHaeguVesselStats(int minutes) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT haegu_no,
                   COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as vessel_count,
                   COALESCE(SUM(distance_nm), 0) as total_distance,
                   COALESCE(AVG(avg_speed), 0) as avg_speed,
                   COUNT(*) as active_tracks
            FROM signal.t_grid_vessel_tracks
            WHERE time_bucket >= NOW() - INTERVAL '%d minutes'
            GROUP BY haegu_no
        """.formatted(minutes);
        
        Map<Integer, VesselStatsResponse> result = new HashMap<>();
        
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getInt("haegu_no"), 
                VesselStatsResponse.builder()
                    .vesselCount(rs.getInt("vessel_count"))
                    .totalDistance(rs.getBigDecimal("total_distance"))
                    .avgSpeed(rs.getBigDecimal("avg_speed"))
                    .activeTracks(rs.getInt("active_tracks"))
                    .build()
            );
        });
        
        return result;
    }
    
    public List<GisBoundaryResponse> getAreaBoundaries() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT area_id, area_name,
                   ST_Y(ST_Centroid(area_geom)) as center_lat,
                   ST_X(ST_Centroid(area_geom)) as center_lon,
                   ST_AsGeoJSON(area_geom) as geom_json
            FROM signal.t_areas
            ORDER BY area_id
        """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> 
            GisBoundaryResponse.builder()
                .areaId(rs.getString("area_id"))
                .areaName(rs.getString("area_name"))
                .centerLat(rs.getDouble("center_lat"))
                .centerLon(rs.getDouble("center_lon"))
                .geomJson(rs.getString("geom_json"))
                .build()
        );
    }
    
    public Map<String, VesselStatsResponse> getAreaVesselStats(int minutes) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        
        String sql = """
            SELECT area_id,
                   COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as vessel_count,
                   COALESCE(SUM(distance_nm), 0) as total_distance,
                   COALESCE(AVG(avg_speed), 0) as avg_speed,
                   COUNT(*) as active_tracks
            FROM signal.t_area_vessel_tracks
            WHERE time_bucket >= NOW() - INTERVAL '%d minutes'
            GROUP BY area_id
        """.formatted(minutes);
        
        Map<String, VesselStatsResponse> result = new HashMap<>();
        
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("area_id"), 
                VesselStatsResponse.builder()
                    .vesselCount(rs.getInt("vessel_count"))
                    .totalDistance(rs.getBigDecimal("total_distance"))
                    .avgSpeed(rs.getBigDecimal("avg_speed"))
                    .activeTracks(rs.getInt("active_tracks"))
                    .build()
            );
        });
        
        return result;
    }
    
    public List<TrackResponse> getHaeguTracks(Integer haeguNo, int minutes) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        List<TrackResponse> allTracks = new ArrayList<>();
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusMinutes(minutes);
        
        // 1시간 이상인 경우 여러 테이블 조합
        if (minutes > 60) {
            // 현재 시간의 정시
            LocalDateTime currentHour = now.withMinute(0).withSecond(0).withNano(0);
            
            if (minutes <= 1440) { // 24시간 이하
                // 1. hourly 테이블에서 과거 데이터 조회
                String hourlySql = """
                    SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                           ST_AsText(t.track_geom) as track_geom,
                           t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                    FROM signal.t_vessel_tracks_hourly t
                    WHERE EXISTS (
                        SELECT 1 FROM signal.t_grid_vessel_tracks g
                        WHERE g.sig_src_cd = t.sig_src_cd 
                            AND g.target_id = t.target_id 
                            AND g.haegu_no = %d
                            AND g.time_bucket >= '%s'
                    )
                    AND t.time_bucket >= '%s'
                    AND t.time_bucket < '%s'
                    ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
                """.formatted(haeguNo, startTime, startTime, currentHour);
                
                allTracks.addAll(jdbcTemplate.query(hourlySql, this::mapTrackResponse));
            } else {
                // daily 테이블 사용 (추후 구현)
            }
            
            // 2. 5min 테이블에서 최근 데이터 조회 (아직 집계되지 않은 부분)
            String recentSql = """
                SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                       ST_AsText(t.track_geom) as track_geom,
                       t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                FROM signal.t_vessel_tracks_5min t
                WHERE EXISTS (
                    SELECT 1 FROM signal.t_grid_vessel_tracks g
                    WHERE g.sig_src_cd = t.sig_src_cd 
                        AND g.target_id = t.target_id 
                        AND g.haegu_no = %d
                        AND g.time_bucket >= '%s'
                )
                AND t.time_bucket >= '%s'
                ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
            """.formatted(haeguNo, currentHour, currentHour);
            
            allTracks.addAll(jdbcTemplate.query(recentSql, this::mapTrackResponse));
            
        } else {
            // 1시간 이하는 5분 테이블만 사용
            String sql = """
                SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                       ST_AsText(t.track_geom) as track_geom,
                       t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                FROM signal.t_vessel_tracks_5min t
                WHERE EXISTS (
                    SELECT 1 FROM signal.t_grid_vessel_tracks g
                    WHERE g.sig_src_cd = t.sig_src_cd 
                        AND g.target_id = t.target_id 
                        AND g.haegu_no = %d
                        AND g.time_bucket >= NOW() - INTERVAL '%d minutes'
                )
                AND t.time_bucket >= NOW() - INTERVAL '%d minutes'
                ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
            """.formatted(haeguNo, minutes, minutes);
            
            allTracks = jdbcTemplate.query(sql, this::mapTrackResponse);
        }
        
        log.debug("Fetched {} tracks for haegu {} in last {} minutes", 
                  allTracks.size(), haeguNo, minutes);
        
        return allTracks;
    }
    
    private TrackResponse mapTrackResponse(ResultSet rs, int rowNum) throws SQLException {
        return TrackResponse.builder()
                .sigSrcCd(rs.getString("sig_src_cd"))
                .targetId(rs.getString("target_id"))
                .timeBucket(rs.getObject("time_bucket", LocalDateTime.class))
                .trackGeom(rs.getString("track_geom"))
                .distanceNm(rs.getBigDecimal("distance_nm"))
                .avgSpeed(rs.getBigDecimal("avg_speed"))
                .maxSpeed(rs.getBigDecimal("max_speed"))
                .pointCount(rs.getInt("point_count"))
                .build();
    }
    
    public List<TrackResponse> getAreaTracks(String areaId, int minutes) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        List<TrackResponse> allTracks = new ArrayList<>();
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusMinutes(minutes);
        
        // 1시간 이상인 경우 여러 테이블 조합
        if (minutes > 60) {
            // 현재 시간의 정시
            LocalDateTime currentHour = now.withMinute(0).withSecond(0).withNano(0);
            
            if (minutes <= 1440) { // 24시간 이하
                // 1. hourly 테이블에서 과거 데이터 조회
                String hourlySql = """
                    SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                           ST_AsText(t.track_geom) as track_geom,
                           t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                    FROM signal.t_vessel_tracks_hourly t
                    WHERE EXISTS (
                        SELECT 1 FROM signal.t_area_vessel_tracks a
                        WHERE a.sig_src_cd = t.sig_src_cd 
                            AND a.target_id = t.target_id 
                            AND a.area_id = '%s'
                            AND a.time_bucket >= '%s'
                    )
                    AND t.time_bucket >= '%s'
                    AND t.time_bucket < '%s'
                    ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
                """.formatted(areaId, startTime, startTime, currentHour);
                
                allTracks.addAll(jdbcTemplate.query(hourlySql, this::mapTrackResponse));
            } else {
                // daily 테이블 사용 (추후 구현)
            }
            
            // 2. 5min 테이블에서 최근 데이터 조회 (아직 집계되지 않은 부분)
            String recentSql = """
                SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                       ST_AsText(t.track_geom) as track_geom,
                       t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                FROM signal.t_vessel_tracks_5min t
                WHERE EXISTS (
                    SELECT 1 FROM signal.t_area_vessel_tracks a
                    WHERE a.sig_src_cd = t.sig_src_cd 
                        AND a.target_id = t.target_id 
                        AND a.area_id = '%s'
                        AND a.time_bucket >= '%s'
                )
                AND t.time_bucket >= '%s'
                ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
            """.formatted(areaId, currentHour, currentHour);
            
            allTracks.addAll(jdbcTemplate.query(recentSql, this::mapTrackResponse));
            
        } else {
            // 1시간 이하는 5분 테이블만 사용
            String sql = """
                SELECT DISTINCT t.sig_src_cd, t.target_id, t.time_bucket,
                       ST_AsText(t.track_geom) as track_geom,
                       t.distance_nm, t.avg_speed, t.max_speed, t.point_count
                FROM signal.t_vessel_tracks_5min t
                WHERE EXISTS (
                    SELECT 1 FROM signal.t_area_vessel_tracks a
                    WHERE a.sig_src_cd = t.sig_src_cd 
                        AND a.target_id = t.target_id 
                        AND a.area_id = '%s'
                        AND a.time_bucket >= NOW() - INTERVAL '%d minutes'
                )
                AND t.time_bucket >= NOW() - INTERVAL '%d minutes'
                ORDER BY t.sig_src_cd, t.target_id, t.time_bucket
            """.formatted(areaId, minutes, minutes);
            
            allTracks = jdbcTemplate.query(sql, this::mapTrackResponse);
        }
        
        log.debug("Fetched {} tracks for area {} in last {} minutes", 
                  allTracks.size(), areaId, minutes);
        
        return allTracks;
    }
    
    public List<CompactVesselTrack> getVesselTracks(VesselTracksRequest request) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        List<CompactVesselTrack> results = new ArrayList<>();
        
        LocalDateTime startTime = request.getStartTime();
        LocalDateTime endTime = request.getEndTime();
        
        for (VesselTracksRequest.VesselIdentifier vessel : request.getVessels()) {
            String vesselId = vessel.getSigSrcCd() + "_" + vessel.getTargetId();
            
            // Determine which tables to query based on time range
            Duration duration = Duration.between(startTime, endTime);
            long hours = duration.toHours();
            
            List<TrackResponse> tracks = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime currentHour = now.withMinute(0).withSecond(0).withNano(0);
            LocalDateTime currentDay = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
            
            if (hours <= 1 || endTime.isAfter(currentHour)) {
                // Query 5min table for recent data
                String sql5min = """
                    SELECT sig_src_cd, target_id, time_bucket,
                           ST_AsText(track_geom) as track_geom,
                           distance_nm, avg_speed, max_speed, point_count
                    FROM signal.t_vessel_tracks_5min
                    WHERE sig_src_cd = ? AND target_id = ?
                    AND time_bucket BETWEEN ? AND ?
                    ORDER BY time_bucket
                """;
                
                LocalDateTime query5minStart = startTime.isAfter(currentHour) ? startTime : currentHour;
                if (endTime.isAfter(currentHour)) {
                    tracks.addAll(jdbcTemplate.query(sql5min, this::mapTrackResponse,
                        vessel.getSigSrcCd(), vessel.getTargetId(),
                        Timestamp.valueOf(query5minStart), Timestamp.valueOf(endTime)));
                }
            }
            
            if (hours > 1 && startTime.isBefore(currentHour)) {
                // Query hourly table
                String sqlHourly = """
                    SELECT sig_src_cd, target_id, time_bucket,
                           ST_AsText(track_geom) as track_geom,
                           distance_nm, avg_speed, max_speed, point_count
                    FROM signal.t_vessel_tracks_hourly
                    WHERE sig_src_cd = ? AND target_id = ?
                    AND time_bucket BETWEEN ? AND ?
                    ORDER BY time_bucket
                """;
                
                LocalDateTime queryHourlyEnd = endTime.isBefore(currentHour) ? endTime : currentHour;
                if (hours <= 24 || startTime.isAfter(currentDay)) {
                    tracks.addAll(jdbcTemplate.query(sqlHourly, this::mapTrackResponse,
                        vessel.getSigSrcCd(), vessel.getTargetId(),
                        Timestamp.valueOf(startTime), Timestamp.valueOf(queryHourlyEnd)));
                }
            }
            
            if (hours > 24 && startTime.isBefore(currentDay)) {
                // Query daily table - time_bucket is DATE type
                String sqlDaily = """
                    SELECT sig_src_cd, target_id, 
                           time_bucket::timestamp as time_bucket,
                           ST_AsText(track_geom) as track_geom,
                           distance_nm, avg_speed, max_speed, point_count
                    FROM signal.t_vessel_tracks_daily
                    WHERE sig_src_cd = ? AND target_id = ?
                    AND time_bucket BETWEEN ?::date AND ?::date
                    ORDER BY time_bucket
                """;
                
                LocalDateTime queryDailyEnd = endTime.isBefore(currentDay) ? endTime : currentDay;
                tracks.addAll(jdbcTemplate.query(sqlDaily, this::mapTrackResponse,
                    vessel.getSigSrcCd(), vessel.getTargetId(),
                    Timestamp.valueOf(startTime), Timestamp.valueOf(queryDailyEnd)));
            }
            
            if (!tracks.isEmpty()) {
                CompactVesselTrack compactTrack = buildCompactVesselTrack(vessel, tracks);
                results.add(compactTrack);
            }
        }
        
        return results;
    }
    
    private CompactVesselTrack buildCompactVesselTrack(
            VesselTracksRequest.VesselIdentifier vessel,
            List<TrackResponse> tracks) {
        
        String vesselId = vessel.getSigSrcCd() + "_" + vessel.getTargetId();
        List<double[]> geometry = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        double totalDistance = 0;
        double maxSpeed = 0;
        int totalPoints = 0;
        
        WKTReader reader = new WKTReader();
        
        for (TrackResponse track : tracks) {
            if (track.getTrackGeom() != null && !track.getTrackGeom().isEmpty()) {
                try {
                    // Parse LineStringM
                    String wkt = track.getTrackGeom();
                    if (wkt.startsWith("LINESTRING M")) {
                        // Extract coordinate data from WKT
                        String coordsPart = wkt.substring("LINESTRING M(".length() + 1, wkt.length() - 1);
                        String[] points = coordsPart.split(",");
                        
                        for (String point : points) {
                            String[] parts = point.trim().split("\\s+");
                            if (parts.length >= 3) {
                                double lon = Double.parseDouble(parts[0]);
                                double lat = Double.parseDouble(parts[1]);
                                String timestamp = parts[2]; // Unix timestamp as string
                                
                                geometry.add(new double[]{lon, lat});
                                timestamps.add(timestamp);
                                
                                // Add SOG value if available (could be from track data)
                                if (track.getAvgSpeed() != null) {
                                    speeds.add(track.getAvgSpeed().doubleValue());
                                } else {
                                    speeds.add(0.0);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse track geometry: {}", e.getMessage());
                }
            }
            
            if (track.getDistanceNm() != null) {
                totalDistance += track.getDistanceNm().doubleValue();
            }
            if (track.getMaxSpeed() != null && track.getMaxSpeed().doubleValue() > maxSpeed) {
                maxSpeed = track.getMaxSpeed().doubleValue();
            }
            if (track.getPointCount() != null) {
                totalPoints += track.getPointCount();
            }
        }
        
        // Calculate average speed
        double avgSpeed = speeds.stream()
            .filter(s -> s > 0)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        
        // Get vessel info
        Map<String, String> vesselInfo = getVesselInfo(vessel.getSigSrcCd(), vessel.getTargetId());
        
        return CompactVesselTrack.builder()
            .vesselId(vesselId)
            .sigSrcCd(vessel.getSigSrcCd())
            .targetId(vessel.getTargetId())
            .geometry(geometry)
            .timestamps(timestamps)
            .speeds(speeds)
            .totalDistance(totalDistance)
            .avgSpeed(avgSpeed)
            .maxSpeed(maxSpeed)
            .pointCount(geometry.size())
            .shipName(vesselInfo.get("ship_name"))
            .shipType(vesselInfo.get("ship_type"))
            .shipKindCode(null) // Not available in current schema
            .build();
    }
    
    private Map<String, String> getVesselInfo(String sigSrcCd, String targetId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
        try {
            String sql = """
                SELECT ship_nm as ship_name, ship_ty as ship_type
                FROM signal.t_vessel_latest_position
                WHERE sig_src_cd = ? AND target_id = ?
                LIMIT 1
            """;
            
            return jdbcTemplate.queryForMap(sql, sigSrcCd, targetId)
                .entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue() != null ? e.getValue().toString() : ""
                ));
        } catch (Exception e) {
            return Map.of("ship_name", "", "ship_type", "");
        }
    }
}
