package gc.mda.signal_batch.domain.vessel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class VesselStatsResponse {
    @JsonProperty("vessel_count")
    private Integer vesselCount;
    
    @JsonProperty("total_distance")
    private BigDecimal totalDistance;
    
    @JsonProperty("avg_speed")
    private BigDecimal avgSpeed;
    
    @JsonProperty("active_tracks")
    private Integer activeTracks;
}
