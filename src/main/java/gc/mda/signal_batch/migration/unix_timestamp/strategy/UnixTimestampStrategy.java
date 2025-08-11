package gc.mda.signal_batch.migration.unix_timestamp.strategy;

import gc.mda.signal_batch.model.VesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unix timestamp 기반 M값 생성
 */
@Slf4j
@Component
public class UnixTimestampStrategy {
    
    public String buildLineStringM(List<VesselTrack.TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return null;
        }
        
        // 포인트가 하나일 경우 복제하여 2개로 만들기 (LineString은 최소 2개 포인트 필요)
        if (trackPoints.size() == 1) {
            VesselTrack.TrackPoint point = trackPoints.get(0);
            long unixTimestamp = point.getTime().toEpochSecond(ZoneOffset.UTC);
            String coord = String.format("%.6f %.6f %d", 
                point.getLon(), 
                point.getLat(), 
                unixTimestamp);
            return "LINESTRING M(" + coord + "," + coord + ")";
        }
        
        // Unix timestamp를 M값으로 사용
        String coordinates = trackPoints.stream()
                .map(point -> {
                    long unixTimestamp = point.getTime().toEpochSecond(ZoneOffset.UTC);
                    return String.format("%.6f %.6f %d", 
                        point.getLon(), 
                        point.getLat(), 
                        unixTimestamp);
                })
                .collect(Collectors.joining(","));
        
        return "LINESTRING M(" + coordinates + ")";
    }
}
