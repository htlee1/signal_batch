package gc.mda.signal_batch.global.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 시간 범위 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeRange {
    private LocalDateTime start;
    private LocalDateTime end;
}