package gc.mda.signal_batch.domain.vessel.service.simplification;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * 궤적 간소화 전략 구현
 * 요청 범위와 기간에 따라 동적으로 간소화 레벨을 적용
 */
@Slf4j
@Component
public class TrackSimplificationStrategy {

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * 간소화 레벨 정의
     */
    public enum SimplificationLevel {
        NONE(1.0, 0.0),           // 원본 (간소화 없음)
        MINIMAL(0.9, 0.00001),    // 최소 간소화 (90% 유지)
        LIGHT(0.75, 0.0001),      // 경량 간소화 (75% 유지)
        MODERATE(0.5, 0.0005),    // 중간 간소화 (50% 유지) - 0.001 -> 0.0005
        HEAVY(0.25, 0.001),       // 고도 간소화 (25% 유지) - 0.01 -> 0.001
        VERY_HEAVY(0.2, 0.0015),  // 매우 강한 간소화 (20% 유지)
        EXTREME(0.1, 0.002);      // 극도 간소화 (10% 유지) - 0.1 -> 0.002

        private final double retentionRatio;  // 유지 비율
        private final double tolerance;       // Douglas-Peucker 허용치

        SimplificationLevel(double retentionRatio, double tolerance) {
            this.retentionRatio = retentionRatio;
            this.tolerance = tolerance;
        }

        public double getRetentionRatio() {
            return retentionRatio;
        }

        public double getTolerance() {
            return tolerance;
        }
    }

    /**
     * 요청 기간과 뷰포트 크기에 따른 간소화 레벨 결정
     */
    public SimplificationLevel determineLevel(LocalDateTime startTime, LocalDateTime endTime,
                                              Double minLon, Double maxLon, 
                                              Double minLat, Double maxLat) {
        
        // 시간 범위 계산
        Duration duration = Duration.between(startTime, endTime);
        long hours = duration.toHours();
        
        // 뷰포트 크기 계산
        double viewportWidth = maxLon - minLon;
        double viewportHeight = maxLat - minLat;
        double viewportArea = viewportWidth * viewportHeight;
        
        // 시간 범위별 기본 레벨
        SimplificationLevel baseLevel;
        if (hours <= 1) {
            baseLevel = SimplificationLevel.NONE;
        } else if (hours <= 6) {
            baseLevel = SimplificationLevel.MINIMAL;
        } else if (hours <= 24) {
            baseLevel = SimplificationLevel.LIGHT;
        } else if (hours <= 72) {
            baseLevel = SimplificationLevel.MODERATE;
        } else if (hours <= 168) {  // 1주일
            baseLevel = SimplificationLevel.HEAVY;
        } else {
            baseLevel = SimplificationLevel.EXTREME;
        }
        
        // 뷰포트 크기에 따른 조정
        if (viewportArea > 100) {  // 매우 넓은 영역
            baseLevel = adjustLevel(baseLevel, 1);  // 한 단계 더 간소화
        } else if (viewportArea < 1) {  // 매우 좁은 영역
            baseLevel = adjustLevel(baseLevel, -1);  // 한 단계 덜 간소화
        }
        
        log.info("Simplification level determined: {} ({}h, area: {})", 
                baseLevel, hours, String.format("%.2f", viewportArea));
        
        return baseLevel;
    }

    /**
     * 간소화 레벨 조정
     */
    private SimplificationLevel adjustLevel(SimplificationLevel current, int adjustment) {
        SimplificationLevel[] levels = SimplificationLevel.values();
        int currentIndex = current.ordinal();
        int newIndex = Math.max(0, Math.min(levels.length - 1, currentIndex + adjustment));
        return levels[newIndex];
    }

    /**
     * LineStringM 궤적 간소화
     */
    public String simplifyTrack(String wktLineString, SimplificationLevel level) {
        if (level == SimplificationLevel.NONE) {
            return wktLineString;
        }

        try {
            // WKT 파싱
            LineString original = parseLineStringM(wktLineString);
            if (original == null || original.getNumPoints() < 3) {
                return wktLineString;
            }

            // Douglas-Peucker 간소화
            DouglasPeuckerSimplifier simplifier = new DouglasPeuckerSimplifier(original);
            simplifier.setDistanceTolerance(level.getTolerance());
            LineString simplified = (LineString) simplifier.getResultGeometry();

            // 중요 포인트 보존 (시작점, 끝점, 방향 전환점)
            LineString result = preserveKeyPoints(original, simplified, level);

            // WKT로 변환
            return convertToLineStringM(result, wktLineString);

        } catch (Exception e) {
            log.error("Error simplifying track: {}", e.getMessage());
            return wktLineString;
        }
    }

    /**
     * 포인트 수 기반 간소화
     */
    public String simplifyByPointCount(String wktLineString, int maxPoints) {
        try {
            LineString original = parseLineStringM(wktLineString);
            if (original == null || original.getNumPoints() <= maxPoints) {
                return wktLineString;
            }

            // 유지할 포인트 비율 계산
            double ratio = (double) maxPoints / original.getNumPoints();
            
            // 적절한 간소화 레벨 찾기
            SimplificationLevel level = SimplificationLevel.NONE;
            for (SimplificationLevel l : SimplificationLevel.values()) {
                if (l.getRetentionRatio() <= ratio) {
                    level = l;
                    break;
                }
            }

            return simplifyTrack(wktLineString, level);

        } catch (Exception e) {
            log.error("Error simplifying by point count: {}", e.getMessage());
            return wktLineString;
        }
    }

    /**
     * 적응형 간소화 - 데이터 밀도에 따라 동적 조정
     */
    public String adaptiveSimplify(String wktLineString, double targetSizeKB) {
        try {
            // 현재 크기 추정 (포인트당 약 24바이트)
            LineString original = parseLineStringM(wktLineString);
            if (original == null) return wktLineString;
            
            double currentSizeKB = (original.getNumPoints() * 24.0) / 1024.0;
            
            if (currentSizeKB <= targetSizeKB) {
                return wktLineString;
            }

            // 목표 포인트 수 계산
            int targetPoints = (int) ((targetSizeKB * 1024) / 24);
            return simplifyByPointCount(wktLineString, targetPoints);

        } catch (Exception e) {
            log.error("Error in adaptive simplification: {}", e.getMessage());
            return wktLineString;
        }
    }

    /**
     * LineStringM 파싱 (M값 포함)
     */
    private LineString parseLineStringM(String wkt) {
        if (wkt == null || wkt.isEmpty()) return null;
        
        try {
            // LINESTRING M(...) 형식 파싱
            String coords = wkt.replace("LINESTRING M(", "")
                              .replace("LINESTRING(", "")
                              .replace(")", "")
                              .trim();
            
            if (coords.isEmpty()) return null;
            
            String[] points = coords.split(",");
            List<Coordinate> coordinates = new ArrayList<>();
            
            for (String point : points) {
                String[] parts = point.trim().split("\\s+");
                if (parts.length >= 2) {
                    double x = Double.parseDouble(parts[0]);
                    double y = Double.parseDouble(parts[1]);
                    // M값은 있으면 사용, 없으면 무시
                    double m = parts.length >= 3 ? Double.parseDouble(parts[2]) : Double.NaN;
                    Coordinate coord = new Coordinate(x, y);
                    coord.setM(m);
                    coordinates.add(coord);
                }
            }
            
            if (coordinates.size() < 2) return null;
            
            return geometryFactory.createLineString(
                coordinates.toArray(new Coordinate[0])
            );
            
        } catch (Exception e) {
            log.error("Error parsing LineStringM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * LineString을 LineStringM WKT로 변환
     */
    private String convertToLineStringM(LineString simplified, String originalWkt) {
        StringBuilder sb = new StringBuilder();
        
        // 원본이 M값을 가지고 있는지 확인
        boolean hasM = originalWkt.contains("LINESTRING M");
        
        if (hasM) {
            sb.append("LINESTRING M(");
        } else {
            sb.append("LINESTRING(");
        }
        
        Coordinate[] coords = simplified.getCoordinates();
        
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(", ");
            
            sb.append(String.format("%.6f %.6f", coords[i].x, coords[i].y));
            
            // M값이 있으면 추가
            if (hasM) {
                double m = Double.isNaN(coords[i].getM()) ? 
                          (double)i / (coords.length - 1) : coords[i].getM();
                sb.append(String.format(" %.6f", m));
            }
        }
        
        sb.append(")");
        return sb.toString();
    }

    /**
     * 중요 포인트 보존
     */
    private LineString preserveKeyPoints(LineString original, LineString simplified, 
                                       SimplificationLevel level) {
        List<Coordinate> result = new ArrayList<>();
        
        // 시작점과 끝점은 항상 보존
        result.add(original.getCoordinateN(0));
        
        // 간소화된 중간 포인트들
        for (int i = 1; i < simplified.getNumPoints() - 1; i++) {
            result.add(simplified.getCoordinateN(i));
        }
        
        // 끝점
        result.add(original.getCoordinateN(original.getNumPoints() - 1));
        
        return geometryFactory.createLineString(
            result.toArray(new Coordinate[0])
        );
    }

    /**
     * 배치 간소화 - 여러 궤적을 한번에 처리
     */
    public List<String> batchSimplify(List<String> tracks, SimplificationLevel level) {
        return tracks.parallelStream()
            .map(track -> simplifyTrack(track, level))
            .collect(Collectors.toList());
    }
}