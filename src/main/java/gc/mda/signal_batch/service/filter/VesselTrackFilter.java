package gc.mda.signal_batch.service.filter;

import gc.mda.signal_batch.dto.websocket.TrackQueryRequest;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 선박 궤적 데이터의 거리/속도 기반 필터링을 수행하는 컴포넌트
 * bucket 간 연결성을 고려한 정확한 거리 계산 포함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VesselTrackFilter {
    
    private final JdbcTemplate queryJdbcTemplate;
    private final WKTReader wktReader = new WKTReader();
    
    /**
     * 거리/속도 기반으로 선박을 필터링
     */
    public Set<String> filterVesselsByDistanceAndSpeed(TrackQueryRequest request, String tableName) {
        if (!hasDistanceOrSpeedFilter(request)) {
            return Collections.emptySet(); // 필터가 없으면 빈 Set 반환 (모든 선박 포함)
        }
        
        log.info("Filtering vessels by distance/speed from table: {}", tableName);
        
        // 1단계: 기간 내 모든 선박의 궤적 데이터 조회
        Map<String, List<VesselTrackSegment>> vesselTracks = loadVesselTracks(request, tableName);
        
        // 2단계: 각 선박별로 전체 거리와 평균 속도 계산
        Map<String, VesselMetrics> vesselMetrics = calculateVesselMetrics(vesselTracks, request.getIncludeInterBucketDistance());
        
        // 3단계: 필터 조건에 맞는 선박만 선택
        Set<String> filteredVessels = filterVessels(vesselMetrics, request);
        
        log.info("Filtered {} vessels out of {} total vessels", filteredVessels.size(), vesselTracks.size());
        
        return filteredVessels;
    }
    
    /**
     * 여러 테이블에서 조회한 선박 궤적을 통합하여 필터링
     */
    public Set<String> filterVesselsByDistanceAndSpeedMultipleTables(
            TrackQueryRequest request, 
            Map<String, List<TimeRange>> tableStrategyMap) {
        
        if (!hasDistanceOrSpeedFilter(request)) {
            return Collections.emptySet();
        }
        
        log.info("Filtering vessels across multiple tables: {}", tableStrategyMap.keySet());
        
        // 모든 테이블에서 선박별 궤적 데이터 수집
        Map<String, List<VesselTrackSegment>> allVesselTracks = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, List<TimeRange>> entry : tableStrategyMap.entrySet()) {
            String tableName = entry.getKey();
            List<TimeRange> timeRanges = entry.getValue();
            
            for (TimeRange range : timeRanges) {
                TrackQueryRequest rangeRequest = request.toBuilder()
                    .startTime(range.getStart())
                    .endTime(range.getEnd())
                    .build();
                
                Map<String, List<VesselTrackSegment>> tableVesselTracks = 
                    loadVesselTracks(rangeRequest, tableName);
                
                // 기존 데이터와 병합
                for (Map.Entry<String, List<VesselTrackSegment>> vesselEntry : tableVesselTracks.entrySet()) {
                    allVesselTracks.computeIfAbsent(vesselEntry.getKey(), k -> new ArrayList<>())
                        .addAll(vesselEntry.getValue());
                }
            }
        }
        
        // 병합된 데이터로 메트릭 계산
        Map<String, VesselMetrics> vesselMetrics = calculateVesselMetrics(
            allVesselTracks, request.getIncludeInterBucketDistance());
        
        // 필터 적용
        Set<String> filteredVessels = filterVessels(vesselMetrics, request);
        
        log.info("Filtered {} vessels out of {} total vessels across all tables", 
            filteredVessels.size(), allVesselTracks.size());
        
        return filteredVessels;
    }
    
    /**
     * 거리/속도 필터가 있는지 확인
     */
    private boolean hasDistanceOrSpeedFilter(TrackQueryRequest request) {
        return request.getMinTotalDistance() != null ||
               request.getMaxTotalDistance() != null ||
               request.getMinAvgSpeed() != null ||
               request.getMaxAvgSpeed() != null;
    }
    
    /**
     * 선박별 궤적 데이터 로드
     */
    private Map<String, List<VesselTrackSegment>> loadVesselTracks(TrackQueryRequest request, String tableName) {
        String sql = """
            SELECT sig_src_cd, target_id, time_bucket, 
                   ST_AsText(track_geom) as track_geom,  -- WKT 형식으로 변환
                   distance_nm, avg_speed, point_count,
                   LEAD(time_bucket) OVER (PARTITION BY sig_src_cd, target_id ORDER BY time_bucket) as next_bucket,
                   LEAD(ST_AsText(track_geom)) OVER (PARTITION BY sig_src_cd, target_id ORDER BY time_bucket) as next_geom  -- WKT 형식으로 변환
            FROM %s
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY sig_src_cd, target_id, time_bucket
            """.formatted(tableName);
        
        Map<String, List<VesselTrackSegment>> vesselTracks = new ConcurrentHashMap<>();
        
        queryJdbcTemplate.query(sql, rs -> {
            String vesselId = rs.getString("sig_src_cd") + "_" + rs.getString("target_id");
            
            VesselTrackSegment segment = VesselTrackSegment.builder()
                .vesselId(vesselId)
                .timeBucket(rs.getTimestamp("time_bucket").toLocalDateTime())
                .trackGeom(rs.getString("track_geom"))
                .distanceNm(rs.getDouble("distance_nm"))
                .avgSpeed(rs.getDouble("avg_speed"))
                .pointCount(rs.getInt("point_count"))
                .nextBucket(rs.getTimestamp("next_bucket") != null ? 
                    rs.getTimestamp("next_bucket").toLocalDateTime() : null)
                .nextGeom(rs.getString("next_geom"))
                .build();
            
            vesselTracks.computeIfAbsent(vesselId, k -> new ArrayList<>()).add(segment);
        }, Timestamp.valueOf(request.getStartTime()), Timestamp.valueOf(request.getEndTime()));
        
        return vesselTracks;
    }
    
    /**
     * 선박별 메트릭 계산 (전체 거리, 평균 속도)
     */
    private Map<String, VesselMetrics> calculateVesselMetrics(
            Map<String, List<VesselTrackSegment>> vesselTracks,
            boolean includeInterBucketDistance) {
        
        Map<String, VesselMetrics> metrics = new HashMap<>();
        
        for (Map.Entry<String, List<VesselTrackSegment>> entry : vesselTracks.entrySet()) {
            String vesselId = entry.getKey();
            List<VesselTrackSegment> segments = entry.getValue();
            
            double totalDistance = 0.0;
            double totalTime = 0.0;
            int totalPoints = 0;
            
            for (int i = 0; i < segments.size(); i++) {
                VesselTrackSegment segment = segments.get(i);
                
                // 세그먼트 내 거리
                totalDistance += segment.getDistanceNm();
                totalPoints += segment.getPointCount();
                
                // bucket 간 거리 계산 (옵션)
                if (includeInterBucketDistance && segment.getNextBucket() != null && i < segments.size() - 1) {
                    double interBucketDistance = calculateInterBucketDistance(segment);
                    if (interBucketDistance > 0) {
                        totalDistance += interBucketDistance;
                        
                        // 시간 계산 (bucket 간격)
                        double timeDiff = java.time.Duration.between(
                            segment.getTimeBucket(), 
                            segment.getNextBucket()
                        ).toMinutes() / 60.0; // hours
                        totalTime += timeDiff;
                    }
                }
            }
            
            // 전체 시간 계산 (첫 bucket ~ 마지막 bucket)
            if (!segments.isEmpty()) {
                LocalDateTime firstTime = segments.get(0).getTimeBucket();
                LocalDateTime lastTime = segments.get(segments.size() - 1).getTimeBucket();
                totalTime = java.time.Duration.between(firstTime, lastTime).toMinutes() / 60.0; // hours
            }
            
            // 평균 속도 계산
            double avgSpeed = totalTime > 0 ? totalDistance / totalTime : 0.0;
            
            metrics.put(vesselId, VesselMetrics.builder()
                .vesselId(vesselId)
                .totalDistance(totalDistance)
                .avgSpeed(avgSpeed)
                .totalPoints(totalPoints)
                .segmentCount(segments.size())
                .build());
        }
        
        return metrics;
    }
    
    /**
     * bucket 간 거리 계산 (마지막 점과 다음 첫 점 사이)
     */
    private double calculateInterBucketDistance(VesselTrackSegment segment) {
        try {
            if (segment.getTrackGeom() == null || segment.getNextGeom() == null) {
                return 0.0;
            }
            
            // 빈 문자열 체크
            String currentGeom = segment.getTrackGeom().trim();
            String nextGeom = segment.getNextGeom().trim();
            
            if (currentGeom.isEmpty() || nextGeom.isEmpty()) {
                return 0.0;
            }
            
            // LINESTRING EMPTY 체크
            if (currentGeom.equalsIgnoreCase("LINESTRING EMPTY") || 
                nextGeom.equalsIgnoreCase("LINESTRING EMPTY")) {
                return 0.0;
            }
            
            // LINESTRING M 형식 체크 (PostGIS의 M 값을 포함한 LineString)
            if (currentGeom.toUpperCase().contains("LINESTRING M") && !currentGeom.contains("(")) {
                log.debug("Invalid LINESTRING M format: {}", currentGeom.substring(0, Math.min(50, currentGeom.length())));
                return 0.0;
            }
            
            LineString currentLine = (LineString) wktReader.read(currentGeom);
            LineString nextLine = (LineString) wktReader.read(nextGeom);
            
            if (currentLine.isEmpty() || nextLine.isEmpty() || 
                currentLine.getNumPoints() == 0 || nextLine.getNumPoints() == 0) {
                return 0.0;
            }
            
            // 현재 궤적의 마지막 점
            Coordinate lastPoint = currentLine.getCoordinateN(currentLine.getNumPoints() - 1);
            // 다음 궤적의 첫 점
            Coordinate firstPoint = nextLine.getCoordinateN(0);
            
            // 거리 계산 (Haversine formula)
            return calculateHaversineDistance(
                lastPoint.y, lastPoint.x,  // lat, lon
                firstPoint.y, firstPoint.x
            );
            
        } catch (ParseException e) {
            // 상세한 디버그 정보 출력
            String currentSample = segment.getTrackGeom() != null ? 
                segment.getTrackGeom().substring(0, Math.min(100, segment.getTrackGeom().length())) : "null";
            String nextSample = segment.getNextGeom() != null ? 
                segment.getNextGeom().substring(0, Math.min(100, segment.getNextGeom().length())) : "null";
            
            // WKB 형식인지 확인 (16진수로 시작하는 경우)
            if (currentSample.matches("^[0-9A-Fa-f]+$")) {
                log.error("Geometry is in WKB format, not WKT. Please use ST_AsText() in SQL query. Sample: {}", 
                    currentSample.substring(0, Math.min(50, currentSample.length())));
            } else {
                log.warn("Failed to parse geometry: {} - Current: {}, Next: {}", 
                    e.getMessage(), currentSample, nextSample);
            }
            return 0.0;
        } catch (Exception e) {
            log.warn("Unexpected error in calculateInterBucketDistance: {}", e.getMessage(), e);
            return 0.0;
        }
    }
    
    /**
     * Haversine 공식을 사용한 거리 계산 (nautical miles)
     */
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 3440.065; // 지구 반경 (nautical miles)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
    
    /**
     * 필터 조건에 맞는 선박 선택
     */
    private Set<String> filterVessels(Map<String, VesselMetrics> vesselMetrics, TrackQueryRequest request) {
        return vesselMetrics.entrySet().stream()
            .filter(entry -> {
                VesselMetrics metrics = entry.getValue();
                
                // 최소 거리 필터
                if (request.getMinTotalDistance() != null && 
                    metrics.getTotalDistance() < request.getMinTotalDistance()) {
                    return false;
                }
                
                // 최대 거리 필터
                if (request.getMaxTotalDistance() != null && 
                    metrics.getTotalDistance() > request.getMaxTotalDistance()) {
                    return false;
                }
                
                // 최소 속도 필터
                if (request.getMinAvgSpeed() != null && 
                    metrics.getAvgSpeed() < request.getMinAvgSpeed()) {
                    return false;
                }
                
                // 최대 속도 필터
                if (request.getMaxAvgSpeed() != null && 
                    metrics.getAvgSpeed() > request.getMaxAvgSpeed()) {
                    return false;
                }
                
                return true;
            })
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
    
    /**
     * 선박별 메트릭 정보를 반환 (디버깅/통계용)
     */
    public Map<String, VesselMetrics> getVesselMetrics(TrackQueryRequest request, String tableName) {
        Map<String, List<VesselTrackSegment>> vesselTracks = loadVesselTracks(request, tableName);
        return calculateVesselMetrics(vesselTracks, request.getIncludeInterBucketDistance());
    }
    
    @Data
    @Builder
    private static class VesselTrackSegment {
        private String vesselId;
        private LocalDateTime timeBucket;
        private String trackGeom;
        private double distanceNm;
        private double avgSpeed;
        private int pointCount;
        private LocalDateTime nextBucket;
        private String nextGeom;
    }
    
    /**
     * 시간 범위를 나타내는 내부 클래스
     */
    @Data
    @Builder
    public static class TimeRange {
        private LocalDateTime start;
        private LocalDateTime end;
    }
    
    @Data
    @Builder
    public static class VesselMetrics {
        private String vesselId;
        private double totalDistance;
        private double avgSpeed;
        private int totalPoints;
        private int segmentCount;
    }
}
