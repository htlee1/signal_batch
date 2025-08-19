package gc.mda.signal_batch.domain.passage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "순차 구역 통과 조회 요청")
public class SequentialPassageRequest {
    
    @NotNull(message = "조회 시작 시간은 필수입니다")
    @Schema(description = "조회 시작 시간", example = "2025-08-01T00:00:00", required = true)
    private LocalDateTime startTime;
    
    @NotNull(message = "조회 종료 시간은 필수입니다")
    @Schema(description = "조회 종료 시간", example = "2025-08-07T23:59:59", required = true)
    private LocalDateTime endTime;
    
    @Schema(description = "조회 유형 (GRID: 해구, AREA: 사용자정의구역)", example = "GRID", required = true)
    @NotNull(message = "조회 유형은 필수입니다")
    private PassageType type;
    
    @Size(min = 2, max = 10, message = "구역 ID는 최소 2개, 최대 10개까지 지정 가능합니다")
    @Schema(description = "순차 통과 구역 목록 (GRID: 해구번호, AREA: 구역ID)", example = "[\"93\", \"92\", \"100\"]")
    private List<String> zoneIds;
    
    @Schema(description = "순차 통과 여부 (true: 순서대로 통과, false: 모든 구역 통과)", example = "true", defaultValue = "true")
    @Builder.Default
    private Boolean sequentialOnly = true;
    
    public enum PassageType {
        GRID, AREA
    }
}
