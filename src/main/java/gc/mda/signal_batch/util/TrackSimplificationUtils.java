package gc.mda.signal_batch.util;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;


/**
 * 궤적 간소화 유틸리티
 * 이동이 거의 없는 포인트들을 제거하여 궤적을 간소화
 */
@Slf4j
public class TrackSimplificationUtils {
    
    // 지구 반경 (미터)
    private static final double EARTH_RADIUS_M = 6371000.0;
    
    /**
     * 시간별 집계용 궤적 간소화
     * 10m 이내 이동은 생략, 최대 10분(600초) 간격으로 포인트 유지
     */
    public static String simplifyHourlyTrack(String trackWKT) {
        return simplifyTrack(trackWKT, 10.0, 600.0);
    }
    
    /**
     * 일별 집계용 궤적 간소화
     * 20m 이내 이동은 생략, 최대 30분(1800초) 간격으로 포인트 유지
     */
    public static String simplifyDailyTrack(String trackWKT) {
        return simplifyTrack(trackWKT, 20.0, 1800.0);
    }
    
    /**
     * 궤적 간소화 메인 로직
     * @param trackWKT LineStringM 형식의 WKT 문자열
     * @param minDistanceM 최소 이동거리 (미터)
     * @param maxTimeGapSeconds 최대 시간 간격 (초)
     * @return 간소화된 LineStringM WKT
     */
    private static String simplifyTrack(String trackWKT, double minDistanceM, double maxTimeGapSeconds) {
        if (trackWKT == null || trackWKT.isEmpty()) {
            return trackWKT;
        }
        
        try {
            // LINESTRING M 형식에서 포인트 추출
            if (!trackWKT.startsWith("LINESTRING M")) {
                return trackWKT;
            }
            
            // 괄호 안의 내용만 추출
            int startIdx = trackWKT.indexOf('(');
            int endIdx = trackWKT.lastIndexOf(')');
            if (startIdx == -1 || endIdx == -1) {
                return trackWKT;
            }
            
            String pointsStr = trackWKT.substring(startIdx + 1, endIdx);
            
            // 포인트들을 파싱 (쉼표로 분리하되, 괄호 내부는 무시)
            List<String> pointStrList = new ArrayList<>();
            StringBuilder currentPoint = new StringBuilder();
            int parenDepth = 0;
            
            for (char c : pointsStr.toCharArray()) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
                else if (c == ',' && parenDepth == 0) {
                    pointStrList.add(currentPoint.toString().trim());
                    currentPoint = new StringBuilder();
                    continue;
                }
                currentPoint.append(c);
            }
            if (currentPoint.length() > 0) {
                pointStrList.add(currentPoint.toString().trim());
            }
            
            if (pointStrList.size() <= 2) {
                return trackWKT; // 2개 이하 포인트는 간소화 불가
            }
            
            List<PointM> points = new ArrayList<>();
            for (String pointStr : pointStrList) {
                String[] coords = pointStr.trim().split("\\s+");
                if (coords.length >= 3) {
                    try {
                        points.add(new PointM(
                            Double.parseDouble(coords[0]),
                            Double.parseDouble(coords[1]),
                            Double.parseDouble(coords[2])
                        ));
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse point: {}", pointStr);
                        return trackWKT; // 파싱 실패 시 원본 반환
                    }
                }
            }
            
            List<PointM> simplifiedPoints = new ArrayList<>();
            simplifiedPoints.add(points.get(0)); // 시작점
            
            PointM lastAdded = points.get(0);
            double timeSinceLastAdded = 0.0;
            
            for (int i = 1; i < points.size() - 1; i++) {
                PointM current = points.get(i);
                double distance = calculateDistance(lastAdded, current);
                double timeDiff = current.m - lastAdded.m;
                timeSinceLastAdded += timeDiff;
                
                if (distance >= minDistanceM || timeSinceLastAdded >= maxTimeGapSeconds) {
                    simplifiedPoints.add(current);
                    lastAdded = current;
                    timeSinceLastAdded = 0.0;
                }
            }
            
            simplifiedPoints.add(points.get(points.size() - 1)); // 끝점
            
            // 간소화 결과 로깅
            int removedPoints = points.size() - simplifiedPoints.size();
            if (removedPoints > 0) {
                log.debug("궤적 간소화: {} 포인트 중 {} 포인트 제거 ({}% 감소)", 
                    points.size(), removedPoints, 
                    (removedPoints * 100) / points.size());
            }
            
            // LineStringM 재구성
            StringBuilder sb = new StringBuilder("LINESTRING M(");
            for (int i = 0; i < simplifiedPoints.size(); i++) {
                if (i > 0) sb.append(", ");
                PointM p = simplifiedPoints.get(i);
                sb.append(p.x).append(" ").append(p.y).append(" ").append(p.m);
            }
            sb.append(")");
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("궤적 간소화 중 오류 발생", e);
            return trackWKT;
        }
    }
    
    // 포인트M 클래스
    private static class PointM {
        final double x, y, m;
        PointM(double x, double y, double m) {
            this.x = x;
            this.y = y;
            this.m = m;
        }
    }
    
    /**
     * 두 좌표 간 거리 계산 (미터)
     * Haversine 공식 사용
     */
    private static double calculateDistance(PointM p1, PointM p2) {
        double lat1 = Math.toRadians(p1.y);
        double lon1 = Math.toRadians(p1.x);
        double lat2 = Math.toRadians(p2.y);
        double lon2 = Math.toRadians(p2.x);
        
        double dlat = lat2 - lat1;
        double dlon = lon2 - lon1;
        
        double a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dlon / 2) * Math.sin(dlon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_M * c;
    }
    
    /**
     * 간소화 통계 정보 반환
     */
    public static SimplificationStats getSimplificationStats(String originalWKT, String simplifiedWKT) {
        try {
            // 포인트 수 계산
            int originalPoints = countPoints(originalWKT);
            int simplifiedPoints = countPoints(simplifiedWKT);
            int removedPoints = originalPoints - simplifiedPoints;
            double reductionRate = originalPoints > 0 ? (double) removedPoints / originalPoints * 100 : 0.0;
            
            return new SimplificationStats(originalPoints, simplifiedPoints, removedPoints, reductionRate);
        } catch (Exception e) {
            log.error("간소화 통계 계산 중 오류", e);
            return new SimplificationStats(0, 0, 0, 0.0);
        }
    }
    
    private static int countPoints(String wkt) {
        if (wkt == null || !wkt.startsWith("LINESTRING M")) {
            return 0;
        }
        String pointsStr = wkt.substring("LINESTRING M(".length(), wkt.length() - 1);
        return pointsStr.split(",").length;
    }
    
    public static class SimplificationStats {
        public final int originalPoints;
        public final int simplifiedPoints;
        public final int removedPoints;
        public final double reductionRate;
        
        public SimplificationStats(int originalPoints, int simplifiedPoints, int removedPoints, double reductionRate) {
            this.originalPoints = originalPoints;
            this.simplifiedPoints = simplifiedPoints;
            this.removedPoints = removedPoints;
            this.reductionRate = reductionRate;
        }
    }
}
