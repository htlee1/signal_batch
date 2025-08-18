package gc.mda.signal_batch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CompactVesselTrack {
    private String vesselId;
    private String sigSrcCd;
    private String targetId;
    
    private List<double[]> geometry;
    private List<String> timestamps;
    private List<Double> speeds;
    
    private Double totalDistance;
    private Double avgSpeed;
    private Double maxSpeed;
    private Integer pointCount;
    
    private String shipName;
    private String shipType;
    private String shipKindCode;
}
