package gc.mda.signal_batch.dto.websocket;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ViewportFilter {
    private Double minLon;
    private Double minLat;
    private Double maxLon;
    private Double maxLat;
    
    public boolean isValid() {
        return minLon != null && minLat != null && 
               maxLon != null && maxLat != null &&
               minLon < maxLon && minLat < maxLat;
    }
}
