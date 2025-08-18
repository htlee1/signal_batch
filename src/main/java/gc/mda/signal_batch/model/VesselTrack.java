package gc.mda.signal_batch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VesselTrack implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // 기본 식별자
    private String sigSrcCd;
    private String targetId;
    private LocalDateTime timeBucket;
    
    // 궤적 정보
    private List<TrackPoint> trackPoints;
    private String trackGeom;  // MIGRATION_V2: PostGIS LineStringM WKT format (unix timestamp)
    private BigDecimal distanceNm;  // 이동 거리 (해리)
    private BigDecimal avgSpeed;
    private BigDecimal maxSpeed;
    private Integer pointCount;
    
    // 시작/종료 위치
    private TrackPosition startPosition;
    private TrackPosition endPosition;
    
    // 해구/구역 정보 (선택적)
    private Integer haeguNo;
    private String areaId;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    
    private LocalDateTime createdAt;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackPoint implements Serializable {
        private LocalDateTime time;
        private Double lat;
        private Double lon;
        private BigDecimal sog;
        private BigDecimal cog;
        private Integer heading;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackPosition implements Serializable {
        private Double lat;
        private Double lon;
        private LocalDateTime time;
        private BigDecimal sog;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VesselKey implements Serializable {
        private String sigSrcCd;
        private String targetId;
        private LocalDateTime timeBucket;
    }
    
    public String getVesselKey() {
        return sigSrcCd + "_" + targetId;
    }
    
    public boolean hasValidTrack() {
        return trackPoints != null && trackPoints.size() >= 1;  // 1개 이상이면 유효
    }
    
    // 거리 계산 (Haversine formula)
    public BigDecimal calculateDistance() {
        if (!hasValidTrack()) {
            return BigDecimal.ZERO;
        }
        
        double totalDistance = 0.0;
        for (int i = 1; i < trackPoints.size(); i++) {
            TrackPoint prev = trackPoints.get(i - 1);
            TrackPoint curr = trackPoints.get(i);
            totalDistance += calculateDistanceBetweenPoints(
                prev.getLat(), prev.getLon(),
                curr.getLat(), curr.getLon()
            );
        }
        
        return BigDecimal.valueOf(totalDistance).setScale(2, BigDecimal.ROUND_HALF_UP);
    }
    
    private double calculateDistanceBetweenPoints(double lat1, double lon1, double lat2, double lon2) {
        final double R = 3440.065; // 지구 반경 (해리)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }
}
