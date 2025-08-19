package gc.mda.signal_batch.migration.unix_timestamp.strategy;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unix timestamp 기반 M값 생성
 */
@Slf4j
@Component
public class UnixTimestampStrategy {
    
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    
    public String buildLineStringM(List<VesselTrack.TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return null;
        }
        
        // 포인트가 하나일 경우 복제하여 2개로 만들기 (LineString은 최소 2개 포인트 필요)
        if (trackPoints.size() == 1) {
            VesselTrack.TrackPoint point = trackPoints.get(0);
            // LocalDateTime을 KST로 해석하여 UTC epoch로 변환
            long unixTimestamp = ZonedDateTime.of(point.getTime(), KST_ZONE).toEpochSecond();
            String coord = String.format("%.6f %.6f %d", 
                point.getLon(), 
                point.getLat(), 
                unixTimestamp);
            return "LINESTRING M(" + coord + "," + coord + ")";
        }
        
        // Unix timestamp를 M값으로 사용 (KST LocalDateTime → UTC epoch)
        String coordinates = trackPoints.stream()
                .map(point -> {
                    // LocalDateTime을 KST로 해석하여 UTC epoch로 변환
                    long unixTimestamp = ZonedDateTime.of(point.getTime(), KST_ZONE).toEpochSecond();
                    return String.format("%.6f %.6f %d", 
                        point.getLon(), 
                        point.getLat(), 
                        unixTimestamp);
                })
                .collect(Collectors.joining(","));
        
        return "LINESTRING M(" + coordinates + ")";
    }
}