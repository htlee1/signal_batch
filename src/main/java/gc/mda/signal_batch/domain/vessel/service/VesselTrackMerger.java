package gc.mda.signal_batch.domain.vessel.service;

import gc.mda.signal_batch.global.websocket.dto.MergedVesselTrack;
import gc.mda.signal_batch.global.websocket.dto.VesselTrackData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 선박 궤적 병합 서비스
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VesselTrackMerger {
    
    private final JdbcTemplate queryJdbcTemplate;
    
    /**
     * 선박별로 궤적 데이터를 병합
     */
    public List<MergedVesselTrack> mergeTracksByVessel(List<VesselTrackData> tracks) {
        // 선박별로 그룹화
        Map<String, List<VesselTrackData>> vesselGroups = tracks.stream()
            .collect(Collectors.groupingBy(t -> t.getSigSrcCd() + "_" + t.getTargetId()));
        
        log.info("Merging tracks for {} vessels from {} segments", 
                vesselGroups.size(), tracks.size());
        
        List<MergedVesselTrack> mergedTracks = new ArrayList<>();
        
        for (Map.Entry<String, List<VesselTrackData>> entry : vesselGroups.entrySet()) {
            try {
                MergedVesselTrack merged = mergeVesselSegments(entry.getKey(), entry.getValue());
                if (merged != null) {
                    mergedTracks.add(merged);
                }
            } catch (Exception e) {
                log.error("Failed to merge tracks for vessel {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        log.info("Merged {} vessels successfully", mergedTracks.size());
        return mergedTracks;
    }
    
    /**
     * 단일 선박의 세그먼트들을 병합
     */
    private MergedVesselTrack mergeVesselSegments(String vesselId, List<VesselTrackData> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        
        // 유효한 geometry를 가진 세그먼트만 필터링
        List<VesselTrackData> validSegments = segments.stream()
            .filter(s -> s.getTrackGeom() != null 
                        && !s.getTrackGeom().isEmpty() 
                        && s.getTrackGeom().startsWith("LINESTRING M")
                        && !s.getTrackGeom().equals("LINESTRING EMPTY")
                        && !s.getTrackGeom().equals("GEOMETRYCOLLECTION EMPTY"))
            .collect(Collectors.toList());
        
        if (validSegments.isEmpty()) {
            log.trace("No valid geometries for vessel {}", vesselId);
            return null;
        }
        
        // 시간순 정렬
        validSegments.sort(Comparator.comparing(VesselTrackData::getStartTime));
        
        VesselTrackData firstSegment = validSegments.get(0);
        
        // 간단한 병합: 모든 포인트를 시간순으로 연결
        String mergedGeom = mergeLineStringsSimple(validSegments);
        
        // 병합 실패 시 null 반환
        if (mergedGeom == null) {
            return null;
        }
        
        LocalDateTime startTime = validSegments.get(0).getStartTime();
        LocalDateTime endTime = validSegments.get(validSegments.size() - 1).getEndTime() != null ?
            validSegments.get(validSegments.size() - 1).getEndTime() : validSegments.get(validSegments.size() - 1).getStartTime();
        
        // 통계 계산 - 병합된 궤적에서 실제 거리/속도 계산
        Map<String, Object> stats = calculateMergedTrackStats(mergedGeom, startTime, endTime);
        double totalDistance = (Double) stats.get("distance");
        double avgSpeed = (Double) stats.get("avgSpeed");
        
        // 기존 방식은 폴백으로 사용
        if (totalDistance == 0.0) {
            totalDistance = validSegments.stream()
                .mapToDouble(VesselTrackData::getDistanceNm)
                .sum();
        }
        
        int totalPoints = validSegments.stream()
            .mapToInt(VesselTrackData::getPointCount)
            .sum();
        
        List<String> timeBuckets = validSegments.stream()
            .map(s -> s.getStartTime().toString())
            .collect(Collectors.toList());
        
        return MergedVesselTrack.builder()
            .sigSrcCd(firstSegment.getSigSrcCd())
            .targetId(firstSegment.getTargetId())
            .vesselId(vesselId)
            .mergedTrackGeom(mergedGeom)
            .totalDistanceNm(totalDistance)
            .avgSpeed(avgSpeed)
            .startTime(startTime)
            .endTime(endTime)
            .totalPoints(totalPoints)
            .timeBuckets(timeBuckets)
            .build();
    }
    
    /**
     * 간단한 LineString 병합 (모든 포인트를 시간순으로 연결)
     */
    private String mergeLineStringsSimple(List<VesselTrackData> segments) {
        if (segments.size() == 1) {
            return segments.get(0).getTrackGeom();
        }
        
        try {
            // 모든 포인트를 시간순으로 수집
            List<Point> allPoints = new ArrayList<>();
            
            // 첫 번째 세그먼트의 시작 시간을 기준으로 함
            LocalDateTime baseTime = null;
            
            for (VesselTrackData segment : segments) {
                String geom = segment.getTrackGeom();
                if (geom == null || !geom.startsWith("LINESTRING M")) {
                    continue;
                }
                
                // 기준 시간 설정 (첫 번째 유효한 세그먼트)
                if (baseTime == null) {
                    baseTime = segment.getStartTime();
                }
                
                // 현재 세그먼트의 시간 오프셋 계산 (초 단위)
                long segmentOffset = java.time.Duration.between(baseTime, segment.getStartTime()).getSeconds();
                
                // geometry 파싱 - 괄호 처리 개선
                int startIdx = geom.indexOf('(');
                int endIdx = geom.lastIndexOf(')');
                if (startIdx == -1 || endIdx == -1) continue;
                
                String points = geom.substring(startIdx + 1, endIdx);
                
                // 포인트들을 파싱 (쉼표로 분리하되, 괄호 내부는 무시)
                List<String> pointList = new ArrayList<>();
                StringBuilder currentPoint = new StringBuilder();
                int parenDepth = 0;
                
                for (char c : points.toCharArray()) {
                    if (c == '(') parenDepth++;
                    else if (c == ')') parenDepth--;
                    else if (c == ',' && parenDepth == 0) {
                        pointList.add(currentPoint.toString().trim());
                        currentPoint = new StringBuilder();
                        continue;
                    }
                    currentPoint.append(c);
                }
                if (currentPoint.length() > 0) {
                    pointList.add(currentPoint.toString().trim());
                }
                
                for (String pointStr : pointList) {
                    String[] coords = pointStr.trim().split("\\s+");
                    if (coords.length >= 3) {
                        try {
                            double lon = Double.parseDouble(coords[0]);
                            double lat = Double.parseDouble(coords[1]);
                            double m = Double.parseDouble(coords[2]) + segmentOffset; // 시간 오프셋 추가
                            allPoints.add(new Point(lon, lat, m));
                        } catch (NumberFormatException e) {
                            log.debug("Failed to parse point: {}", pointStr);
                        }
                    }
                }
            }
            
            if (allPoints.isEmpty()) {
                return null;
            }
            
            // 중복 제거 및 시간순 정렬
            allPoints = allPoints.stream()
                .distinct()
                .sorted(Comparator.comparingDouble(p -> p.m))
                .collect(Collectors.toList());
            
            // 거리 기반 간소화 (5m 미만 이동은 제거)
            List<Point> simplifiedPoints = simplifyByDistance(allPoints, 5.0);
            
            // LineStringM 재구성
            StringBuilder sb = new StringBuilder("LINESTRING M(");
            for (int i = 0; i < simplifiedPoints.size(); i++) {
                if (i > 0) sb.append(", ");
                Point p = simplifiedPoints.get(i);
                sb.append(p.lon).append(" ").append(p.lat).append(" ").append(p.m);
            }
            sb.append(")");
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to merge LineStrings: {}", e.getMessage());
            // 실패 시 첫 번째 세그먼트 반환
            return segments.get(0).getTrackGeom();
        }
    }
    
    /**
     * 거리 기반 간소화
     */
    private List<Point> simplifyByDistance(List<Point> points, double minDistanceMeters) {
        if (points.size() <= 2) {
            return points;
        }
        
        List<Point> result = new ArrayList<>();
        result.add(points.get(0)); // 시작점
        
        Point lastAdded = points.get(0);
        
        for (int i = 1; i < points.size() - 1; i++) {
            Point current = points.get(i);
            double distance = calculateDistance(lastAdded.lat, lastAdded.lon, current.lat, current.lon);
            
            if (distance >= minDistanceMeters) {
                result.add(current);
                lastAdded = current;
            }
        }
        
        // 마지막 포인트가 포함되지 않았다면 추가 (궤적 끝점 보존)
        Point lastPoint = points.get(points.size() - 1);
        if (!result.get(result.size() - 1).equals(lastPoint)) {
            result.add(lastPoint);
        }
        
        return result;
    }
    
    /**
     * Haversine 공식으로 두 지점 간 거리 계산 (미터 단위)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000; // 지구 반지름 (미터)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
    
    /**
     * 병합된 궤적에서 실제 거리와 평균 속도 계산
     */
    private Map<String, Object> calculateMergedTrackStats(String mergedGeom, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // PostGIS로 실제 거리 계산 (geography로 변환하여 미터 단위 계산)
            String sql = "SELECT ST_Length(ST_GeomFromText(?, 4326)::geography) / 1852.0 as distance_nm";
            Double distance = queryJdbcTemplate.queryForObject(sql, Double.class, mergedGeom);
            
            // 시간차 계산 (초)
            long seconds = java.time.Duration.between(startTime, endTime).getSeconds();
            
            // 평균 속도 계산 (knots)
            double avgSpeed = 0.0;
            if (seconds > 0 && distance != null && distance > 0) {
                avgSpeed = (distance / (seconds / 3600.0));
            }
            
            stats.put("distance", distance != null ? distance : 0.0);
            stats.put("avgSpeed", avgSpeed);
            
        } catch (Exception e) {
            log.debug("Failed to calculate merged track stats: {}", e.getMessage());
            stats.put("distance", 0.0);
            stats.put("avgSpeed", 0.0);
        }
        
        return stats;
    }
    
    /**
     * 포인트 내부 클래스
     */
    private static class Point {
        final double lon;
        final double lat;
        final double m;
        
        Point(double lon, double lat, double m) {
            this.lon = lon;
            this.lat = lat;
            this.m = m;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return Double.compare(point.lon, lon) == 0 &&
                   Double.compare(point.lat, lat) == 0 &&
                   Double.compare(point.m, m) == 0;
        }
    }
}