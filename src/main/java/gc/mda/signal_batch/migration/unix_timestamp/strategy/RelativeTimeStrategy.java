package gc.mda.signal_batch.migration.unix_timestamp.strategy;

import gc.mda.signal_batch.migration.unix_timestamp.MValueStrategy;
import gc.mda.signal_batch.model.VesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 상대시간 M값 처리 전략 (기존 방식)
 */
@Slf4j
@Component("relativeTimeStrategy")
public class RelativeTimeStrategy implements MValueStrategy {
    
    @Override
    public String buildLineStringM(List<VesselTrack.TrackPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        
        LocalDateTime baseTime = points.get(0).getTime();
        
        // 단일 포인트 처리: 같은 포인트를 복사
        if (points.size() == 1) {
            VesselTrack.TrackPoint p = points.get(0);
            String pointStr = String.format("%.6f %.6f %.0f", 
                p.getLon(), p.getLat(), 0.0);
            return "LINESTRING M (" + pointStr + ", " + pointStr + ")";
        }
        
        return "LINESTRING M (" + points.stream()
            .map(p -> {
                long seconds = ChronoUnit.SECONDS.between(baseTime, p.getTime());
                return String.format("%.6f %.6f %.0f", 
                    p.getLon(), p.getLat(), (double)seconds);
            })
            .collect(Collectors.joining(", ")) + ")";
    }
    
    @Override
    public long extractMValue(String wkt, int pointIndex) {
        // WKT 파싱하여 M값 추출
        String[] parts = wkt.replace("LINESTRING M (", "").replace(")", "").split(", ");
        if (pointIndex < parts.length) {
            String[] coords = parts[pointIndex].split(" ");
            if (coords.length >= 3) {
                return Long.parseLong(coords[2].replace(".0", ""));
            }
        }
        return 0;
    }
    
    @Override
    public LocalDateTime convertToDateTime(long mValue, LocalDateTime baseTime) {
        return baseTime.plusSeconds(mValue);
    }
}
