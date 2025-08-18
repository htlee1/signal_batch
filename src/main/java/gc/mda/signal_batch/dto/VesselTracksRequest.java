package gc.mda.signal_batch.dto;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VesselTracksRequest {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<VesselIdentifier> vessels;
    
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VesselIdentifier {
        private String sigSrcCd;
        private String targetId;
    }
}
