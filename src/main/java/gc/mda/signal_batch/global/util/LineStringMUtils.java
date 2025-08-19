package gc.mda.signal_batch.global.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LineStringMUtils {
    
    // 상대시간 재계산 메서드들 제거 (Unix timestamp 전환으로 불필요)
    // rebuildLineStringMForHourly, rebuildLineStringMForDaily 삭제됨
    
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