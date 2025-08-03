package gc.mda.signal_batch.util;

import gc.mda.signal_batch.common.AreaBoundaryCache;
import gc.mda.signal_batch.model.VesselTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackClippingUtils {
    
    private final AreaBoundaryCache boundaryCache;
    
    /**
     * 해구 영역별로 track을 분할
     */
    public List<VesselTrack> clipTracksByHaegu(VesselTrack originalTrack) {
        List<VesselTrack> clippedTracks = new ArrayList<>();
        
        try {
            // 트랙이 지나가는 모든 해구 찾기 (캐시 사용)
            Set<Integer> haeguNumbers = findHaegusForTrack(originalTrack);
            
            for (Integer haeguNo : haeguNumbers) {
                VesselTrack clippedTrack = clipTrackForHaegu(originalTrack, haeguNo);
                if (clippedTrack != null && clippedTrack.hasValidTrack()) {
                    clippedTracks.add(clippedTrack);
                }
            }
        } catch (Exception e) {
            log.error("Failed to clip tracks by haegu: {}", e.getMessage());
            // 실패 시 원본 track 반환 (haeguNo만 설정)
            if (originalTrack.getHaeguNo() != null) {
                clippedTracks.add(originalTrack);
            }
        }
        
        return clippedTracks;
    }
    
    /**
     * 구역별로 track을 분할
     */
    public List<VesselTrack> clipTracksByArea(VesselTrack originalTrack) {
        List<VesselTrack> clippedTracks = new ArrayList<>();
        
        try {
            // 트랙이 지나가는 모든 area 찾기 (캐시 사용)
            Set<String> areaIds = findAreasForTrack(originalTrack);
            
            for (String areaId : areaIds) {
                VesselTrack clippedTrack = clipTrackForArea(originalTrack, areaId);
                if (clippedTrack != null && clippedTrack.hasValidTrack()) {
                    clippedTracks.add(clippedTrack);
                }
            }
        } catch (Exception e) {
            log.error("Failed to clip tracks by area: {}", e.getMessage());
            // 실패 시 원본 track 반환 (areaId만 설정)
            if (originalTrack.getAreaId() != null) {
                clippedTracks.add(originalTrack);
            }
        }
        
        return clippedTracks;
    }
    
    private Set<Integer> findHaegusForTrack(VesselTrack track) {
        Set<Integer> haegus = new HashSet<>();
        
        // 모든 track point가 지나가는 해구 수집
        for (VesselTrack.TrackPoint point : track.getTrackPoints()) {
            haegus.addAll(boundaryCache.findHaegusForPoint(point.getLat(), point.getLon()));
        }
        
        return haegus;
    }
    
    private Set<String> findAreasForTrack(VesselTrack track) {
        Set<String> areas = new HashSet<>();
        
        // 모든 track point가 지나가는 area 수집
        for (VesselTrack.TrackPoint point : track.getTrackPoints()) {
            areas.addAll(boundaryCache.findAreasForPoint(point.getLat(), point.getLon()));
        }
        
        return areas;
    }
    
    private VesselTrack clipTrackForHaegu(VesselTrack originalTrack, Integer haeguNo) {
        try {
            // 해구 영역 내의 포인트만 필터링 (캐시 사용)
            List<VesselTrack.TrackPoint> filteredPoints = originalTrack.getTrackPoints().stream()
                    .filter(p -> boundaryCache.isPointInHaegu(p.getLat(), p.getLon(), haeguNo))
                    .collect(Collectors.toList());
            
            if (filteredPoints.isEmpty()) {
                return null;
            }
            
            return buildClippedTrack(originalTrack, filteredPoints, haeguNo, null);
            
        } catch (Exception e) {
            log.error("Failed to clip track for haegu {}: {}", haeguNo, e.getMessage());
            return null;
        }
    }
    
    private VesselTrack clipTrackForArea(VesselTrack originalTrack, String areaId) {
        try {
            // area 영역 내의 포인트만 필터링 (캐시 사용)
            List<VesselTrack.TrackPoint> filteredPoints = originalTrack.getTrackPoints().stream()
                    .filter(p -> boundaryCache.isPointInArea(p.getLat(), p.getLon(), areaId))
                    .collect(Collectors.toList());
            
            if (filteredPoints.isEmpty()) {
                return null;
            }
            
            return buildClippedTrack(originalTrack, filteredPoints, null, areaId);
            
        } catch (Exception e) {
            log.error("Failed to clip track for area {}: {}", areaId, e.getMessage());
            return null;
        }
    }
    
    private VesselTrack buildClippedTrack(VesselTrack originalTrack, 
                                         List<VesselTrack.TrackPoint> filteredPoints,
                                         Integer haeguNo,
                                         String areaId) {
        // 새로운 track 생성
        VesselTrack clippedTrack = VesselTrack.builder()
                .sigSrcCd(originalTrack.getSigSrcCd())
                .targetId(originalTrack.getTargetId())
                .timeBucket(originalTrack.getTimeBucket())
                .trackPoints(filteredPoints)
                .pointCount(filteredPoints.size())
                .haeguNo(haeguNo)
                .areaId(areaId)
                .build();
        
        // 첫점과 끝점 설정
        VesselTrack.TrackPoint first = filteredPoints.get(0);
        VesselTrack.TrackPoint last = filteredPoints.get(filteredPoints.size() - 1);
        
        clippedTrack.setStartPosition(VesselTrack.TrackPosition.builder()
                .lat(first.getLat())
                .lon(first.getLon())
                .time(first.getTime())
                .sog(first.getSog())
                .build());
        
        clippedTrack.setEndPosition(VesselTrack.TrackPosition.builder()
                .lat(last.getLat())
                .lon(last.getLon())
                .time(last.getTime())
                .sog(last.getSog())
                .build());
        
        // 거리 재계산
        clippedTrack.setDistanceNm(clippedTrack.calculateDistance());
        
        // 속도 재계산
        recalculateSpeed(clippedTrack, filteredPoints);
        
        // LineStringM 재생성
        clippedTrack.setTrackGeom(buildLineStringM(filteredPoints));
        
        // 진입/진출 시간 설정
        clippedTrack.setEntryTime(first.getTime());
        if (!last.equals(first)) {
            clippedTrack.setExitTime(last.getTime());
        }
        
        return clippedTrack;
    }
    
    private void recalculateSpeed(VesselTrack track, List<VesselTrack.TrackPoint> points) {
        java.math.BigDecimal MAX_SPEED_LIMIT = new java.math.BigDecimal("9999.99");
        
        List<java.math.BigDecimal> speeds = points.stream()
                .map(VesselTrack.TrackPoint::getSog)
                .filter(Objects::nonNull)
                .filter(speed -> speed.compareTo(java.math.BigDecimal.ZERO) >= 0)
                .filter(speed -> speed.compareTo(MAX_SPEED_LIMIT) <= 0)
                .collect(Collectors.toList());
        
        if (!speeds.isEmpty()) {
            java.math.BigDecimal sumSpeed = speeds.stream()
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal avgSpeed = sumSpeed.divide(
                java.math.BigDecimal.valueOf(speeds.size()), 2, 
                java.math.BigDecimal.ROUND_HALF_UP
            );
            track.setAvgSpeed(avgSpeed.min(MAX_SPEED_LIMIT));
            track.setMaxSpeed(speeds.stream()
                    .max(java.math.BigDecimal::compareTo)
                    .orElse(java.math.BigDecimal.ZERO));
        } else {
            track.setAvgSpeed(java.math.BigDecimal.ZERO);
            track.setMaxSpeed(java.math.BigDecimal.ZERO);
        }
    }
    
    private String buildLineStringM(List<VesselTrack.TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return null;
        }
        
        // 단일 포인트인 경우 동일한 포인트를 2개로 복사
        if (trackPoints.size() == 1) {
            VesselTrack.TrackPoint point = trackPoints.get(0);
            trackPoints = Arrays.asList(point, point);
        }
        
        java.time.LocalDateTime baseTime = trackPoints.get(0).getTime();
        
        StringBuilder wkt = new StringBuilder("LINESTRING M(");
        for (int i = 0; i < trackPoints.size(); i++) {
            VesselTrack.TrackPoint point = trackPoints.get(i);
            if (i > 0) wkt.append(", ");
            
            long secondsFromBase = java.time.Duration.between(baseTime, point.getTime()).getSeconds();
            wkt.append(String.format("%.6f %.6f %d", 
                    point.getLon(), point.getLat(), secondsFromBase));
        }
        wkt.append(")");
        
        return wkt.toString();
    }
}
