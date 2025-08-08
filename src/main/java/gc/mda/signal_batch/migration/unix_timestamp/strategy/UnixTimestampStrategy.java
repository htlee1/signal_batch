package gc.mda.signal_batch.migration.unix_timestamp.strategy;

import gc.mda.signal_batch.migration.unix_timestamp.MValueStrategy;
import gc.mda.signal_batch.model.VesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unix Timestamp M값 처리 전략 (새로운 방식)
 */
@Slf4j
@Component("unixTimestampStrategy")
public class UnixTimestampStrategy implements MValueStrategy {
    
    private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    
    @Override
    public String buildLineStringM(List<VesselTrack.TrackPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        
        // 단일 포인트 처리: 같은 포인트를 복사
        if (points.size() == 1) {
            VesselTrack.TrackPoint p = points.get(0);
            long unixTime = p.getTime().toEpochSecond(KST_OFFSET);
            String pointStr = String.format("%.6f %.6f %d", 
                p.getLon(), p.getLat(), unixTime);
            return "LINESTRING M (" + pointStr + ", " + pointStr + ")";
        }
        
        return "LINESTRING M (" + points.stream()
            .map(p -> {
                long unixTime = p.getTime().toEpochSecond(KST_OFFSET);
                return String.format("%.6f %.6f %d", 
                    p.getLon(), p.getLat(), unixTime);
            })
            .collect(Collectors.joining(", ")) + ")";
    }
    
    @Override
    public long extractMValue(String wkt, int pointIndex) {
        String[] parts = wkt.replace("LINESTRING M (", "").replace(")", "").split(", ");
        if (pointIndex < parts.length) {
            String[] coords = parts[pointIndex].split(" ");
            if (coords.length >= 3) {
                return Long.parseLong(coords[2]);
            }
        }
        return 0;
    }
    
    @Override
    public LocalDateTime convertToDateTime(long mValue, LocalDateTime baseTime) {
        // Unix timestamp는 baseTime 불필요
        return LocalDateTime.ofEpochSecond(mValue, 0, KST_OFFSET);
    }
}
