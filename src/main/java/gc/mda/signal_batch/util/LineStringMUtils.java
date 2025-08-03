package gc.mda.signal_batch.util;

import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Slf4j
public class LineStringMUtils {
    
    /**
     * LineStringM의 M값을 재계산 (hourly 기준)
     * 각 버킷의 시작 시간 기준으로 경과 초 계산
     */
    public static String rebuildLineStringMForHourly(String wkt, LocalDateTime hourBucket) {
        if (wkt == null || !wkt.startsWith("LINESTRING")) {
            return wkt;
        }
        
        try {
            // ST_MakeLine으로 병합된 경우 M값이 보존되지 않으므로
            // 시간 단위의 첫 포인트를 0으로 하여 순차적으로 증가
            String coords = wkt.substring(wkt.indexOf("(") + 1, wkt.lastIndexOf(")"));
            String[] points = coords.split(", ");
            
            StringBuilder newWkt = new StringBuilder("LINESTRING M(");
            
            // 각 5분 버킷마다 약 60개 포인트가 있다고 가정 (1분에 12개)
            // 시간별로 12개 버킷 = 총 720개 포인트
            double secondsPerPoint = 300.0 / 60; // 5초 간격
            
            for (int i = 0; i < points.length; i++) {
                if (i > 0) newWkt.append(", ");
                
                String[] parts = points[i].trim().split(" ");
                double lon = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                
                // 새로운 M값: hourly 버킷 시작부터의 경과 초
                double newM = i * secondsPerPoint;
                
                newWkt.append(String.format("%.6f %.6f %.0f", lon, lat, newM));
            }
            
            newWkt.append(")");
            return newWkt.toString();
            
        } catch (Exception e) {
            log.error("Failed to rebuild LineStringM for hourly: {}", e.getMessage());
            return wkt;
        }
    }
    
    /**
     * LineStringM의 M값을 재계산 (daily 기준)
     * 일 단위의 첫 포인트를 0으로 하여 경과 초 계산
     */
    public static String rebuildLineStringMForDaily(String wkt, LocalDateTime dayBucket) {
        if (wkt == null || !wkt.startsWith("LINESTRING")) {
            return wkt;
        }
        
        try {
            String coords = wkt.substring(wkt.indexOf("(") + 1, wkt.lastIndexOf(")"));
            String[] points = coords.split(", ");
            
            StringBuilder newWkt = new StringBuilder("LINESTRING M(");
            
            // 하루 24시간 = 288개의 5분 버킷
            // 각 시간별로 약 720개 포인트
            double secondsPerPoint = 3600.0 / 720; // 5초 간격
            
            for (int i = 0; i < points.length; i++) {
                if (i > 0) newWkt.append(", ");
                
                String[] parts = points[i].trim().split(" ");
                double lon = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                
                // 새로운 M값: daily 버킷 시작부터의 경과 초
                double newM = i * secondsPerPoint;
                
                newWkt.append(String.format("%.6f %.6f %.0f", lon, lat, newM));
            }
            
            newWkt.append(")");
            return newWkt.toString();
            
        } catch (Exception e) {
            log.error("Failed to rebuild LineStringM for daily: {}", e.getMessage());
            return wkt;
        }
    }
    
    /**
     * M값 조정 (오프셋 추가)
     */
    public static String adjustMValues(String wkt, long offsetSeconds) {
        if (wkt == null || !wkt.startsWith("LINESTRING M")) {
            return wkt;
        }
        
        try {
            String coords = wkt.substring(wkt.indexOf("(") + 1, wkt.lastIndexOf(")"));
            String[] points = coords.split(",");
            
            StringBuilder newWkt = new StringBuilder("LINESTRING M(");
            
            for (int i = 0; i < points.length; i++) {
                if (i > 0) newWkt.append(",");
                
                String[] parts = points[i].trim().split(" ");
                double lon = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                double m = parts.length > 2 ? Double.parseDouble(parts[2]) : 0.0;
                
                // M값에 오프셋 추가
                double newM = m + offsetSeconds;
                
                newWkt.append(String.format("%.6f %.6f %.0f", lon, lat, newM));
            }
            
            newWkt.append(")");
            return newWkt.toString();
            
        } catch (Exception e) {
            log.error("Failed to adjust M values: {}", e.getMessage());
            return wkt;
        }
    }
    
    /**
     * JSONB 형식의 TrackPosition 파싱
     */
    public static String extractJsonValue(String json, String key) {
        try {
            int keyIndex = json.indexOf("\"" + key + "\"");
            if (keyIndex == -1) return null;
            
            int colonIndex = json.indexOf(":", keyIndex);
            int commaIndex = json.indexOf(",", colonIndex);
            int endIndex = commaIndex != -1 ? commaIndex : json.indexOf("}", colonIndex);
            
            String value = json.substring(colonIndex + 1, endIndex).trim();
            return value.replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }
}
