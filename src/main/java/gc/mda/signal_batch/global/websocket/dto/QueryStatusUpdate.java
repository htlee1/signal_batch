package gc.mda.signal_batch.global.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryStatusUpdate {
    private String queryId;
    private String status; // STARTED, PROCESSING, COMPLETED, CANCELLED, ERROR
    private String message;
    private Double progressPercentage;
}