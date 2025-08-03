package gc.mda.signal_batch.util;

import gc.mda.signal_batch.dto.CompactVesselTrack;
import gc.mda.signal_batch.dto.websocket.MergedVesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MergedVesselTrack을 CompactVesselTrack으로 변환하는 유틸리티
 */
@Slf4j
public class VesselTrackConverter {
    
    private static final WKTReader wktReader = new WKTReader();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * MergedVesselTrack을 CompactVesselTrack으로 변환
     */
    public static CompactVesselTrack toCompactTrack(MergedVesselTrack merged) {
        List<double[]> geometry = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        
        try {
            if (merged.getMergedTrackGeom() != null && !merged.getMergedTrackGeom().isEmpty()) {
                LineString lineString = (LineString) wktReader.read(merged.getMergedTrackGeom());
                
                // LineStringM의 각 좌표를 배열로 변환
                for (Coordinate coord : lineString.getCoordinates()) {
                    geometry.add(new double[]{coord.x, coord.y});
                    
                    // M값(초)을 실제 시간으로 변환
                    if (merged.getStartTime() != null) {
                        long timeMillis = merged.getStartTime().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli() + (long)(coord.getM() * 1000);
                        LocalDateTime pointTime = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(timeMillis), 
                                java.time.ZoneId.systemDefault());
                        timestamps.add(TIMESTAMP_FORMATTER.format(pointTime));
                    }
                    
                    // 속도 정보는 평균속도로 대체 (개별 속도 정보가 없으므로)
                    speeds.add(merged.getAvgSpeed());
                }
            }
        } catch (ParseException e) {
            log.error("Error parsing merged track geometry: {}", e.getMessage());
        }
        
        return CompactVesselTrack.builder()
                .vesselId(merged.getVesselId())
                .sigSrcCd(merged.getSigSrcCd())
                .targetId(merged.getTargetId())
                .geometry(geometry)
                .timestamps(timestamps)
                .speeds(speeds)
                .totalDistance(merged.getTotalDistanceNm())
                .avgSpeed(merged.getAvgSpeed())
                .maxSpeed(merged.getAvgSpeed()) // maxSpeed 정보가 없으면 avgSpeed 사용
                .pointCount(geometry.size())
                .build();
    }
    
    /**
     * MergedVesselTrack 리스트를 CompactVesselTrack 리스트로 변환
     */
    public static List<CompactVesselTrack> toCompactTracks(List<MergedVesselTrack> mergedTracks) {
        List<CompactVesselTrack> compactTracks = new ArrayList<>();
        for (MergedVesselTrack merged : mergedTracks) {
            compactTracks.add(toCompactTrack(merged));
        }
        return compactTracks;
    }
}
