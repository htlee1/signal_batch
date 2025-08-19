package gc.mda.signal_batch.global.websocket.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class VesselTrackData {
    private String sigSrcCd;
    private String targetId;
    private String trackGeom; // LineStringM as WKT
    private Double distanceNm;
    private Double avgSpeed;
    private Double maxSpeed;
    private Integer pointCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}