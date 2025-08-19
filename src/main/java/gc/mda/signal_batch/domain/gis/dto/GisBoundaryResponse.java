package gc.mda.signal_batch.domain.gis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GisBoundaryResponse {
    @JsonProperty("haegu_no")
    private Integer haeguNo;
    
    @JsonProperty("area_id")
    private String areaId;
    
    @JsonProperty("area_name")
    private String areaName;
    
    @JsonProperty("geom_json")
    private String geomJson; // GeoJSON 형식의 geometry
    
    @JsonProperty("center_lat")
    private Double centerLat;
    
    @JsonProperty("center_lon")
    private Double centerLon;
}
