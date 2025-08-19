package gc.mda.signal_batch.domain.track.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 비정상 궤적 통계 응답 DTO
 */
@Data
@Builder
public class AbnormalTrackStatsResponse {
    private LocalDate statDate;
    private String abnormalType;
    private Integer vesselCount;
    private Integer trackCount;
    private Integer totalPoints;
    private BigDecimal avgDeviation;
    private BigDecimal maxDeviation;
}