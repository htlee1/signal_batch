package gc.mda.signal_batch.global.websocket.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryResponse {
    private String queryId;
    private String status;
    private String message;
    private Long timestamp;
    
    public static QueryResponse started(String queryId) {
        return QueryResponse.builder()
            .queryId(queryId)
            .status("STARTED")
            .message("Query started successfully")
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static QueryResponse cancelled(String queryId) {
        return QueryResponse.builder()
            .queryId(queryId)
            .status("CANCELLED")
            .message("Query cancelled")
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static QueryResponse error(String queryId, String message) {
        return QueryResponse.builder()
            .queryId(queryId)
            .status("ERROR")
            .message(message)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}