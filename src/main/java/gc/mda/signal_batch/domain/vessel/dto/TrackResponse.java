package gc.mda.signal_batch.domain.vessel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TrackResponse {
    @JsonProperty("sig_src_cd")
    private String sigSrcCd;
    
    @JsonProperty("target_id")
    private String targetId;
    
    @JsonProperty("track_geom")
    private String trackGeom; // WKT format
    
    @JsonProperty("distance_nm")
    private BigDecimal distanceNm;
    
    @JsonProperty("avg_speed")
    private BigDecimal avgSpeed;
    
    @JsonProperty("max_speed")
    private BigDecimal maxSpeed;
    
    @JsonProperty("point_count")
    private Integer pointCount;
    
    @JsonProperty("time_bucket")
    private LocalDateTime timeBucket;
}
