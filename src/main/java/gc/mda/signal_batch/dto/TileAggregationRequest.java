package gc.mda.signal_batch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TileAggregationRequest {
    private LocalDateTime fromDate;
    private LocalDateTime toDate;
    private String tileId;
}
