package gc.mda.signal_batch.util;

import gc.mda.signal_batch.dto.CompactVesselTrack;
import gc.mda.signal_batch.dto.websocket.MergedVesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MergedVesselTrack을 CompactVesselTrack으로 변환하는 유틸리티
 */
@Slf4j
@Component
public class VesselTrackConverter {
    
    private static final WKTReader wktReader = new WKTReader();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // track_geom_v2 고정 사용
    
    /**
     * MergedVesselTrack을 CompactVesselTrack으로 변환
     */
    public CompactVesselTrack toCompactTrack(MergedVesselTrack merged) {
        List<double[]> geometry = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        
        try {
            if (merged.getMergedTrackGeom() != null && !merged.getMergedTrackGeom().isEmpty()) {
                LineString lineString = (LineString) wktReader.read(merged.getMergedTrackGeom());
                
                // Unix timestamp를 String으로 변환
                List<String> unixTimestamps = new ArrayList<>();
                for (Coordinate coord : lineString.getCoordinates()) {
                    geometry.add(new double[]{coord.x, coord.y});
                    // Unix timestamp를 String으로 저장
                    unixTimestamps.add(String.valueOf((long)coord.getM()));
                    speeds.add(merged.getAvgSpeed());
                }
                timestamps = unixTimestamps;
            } else {
                timestamps = new ArrayList<>();
            }
        } catch (ParseException e) {
            log.error("Error parsing merged track geometry: {}", e.getMessage());
            timestamps = new ArrayList<>();
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
                .maxSpeed(merged.getAvgSpeed())
                .pointCount(geometry.size())
                .build();
    }
    
    /**
     * MergedVesselTrack 리스트를 CompactVesselTrack 리스트로 변환
     */
    public List<CompactVesselTrack> toCompactTracks(List<MergedVesselTrack> mergedTracks) {
        List<CompactVesselTrack> compactTracks = new ArrayList<>();
        for (MergedVesselTrack merged : mergedTracks) {
            compactTracks.add(toCompactTrack(merged));
        }
        return compactTracks;
    }
}
