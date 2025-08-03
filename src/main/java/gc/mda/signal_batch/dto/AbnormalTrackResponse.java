package gc.mda.signal_batch.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 비정상 궤적 응답 DTO
 */
@Data
@Builder
public class AbnormalTrackResponse {
    private Long id;
    private String sigSrcCd;
    private String targetId;
    private String vesselId;  // sigSrcCd:targetId
    private LocalDateTime timeBucket;
    private String abnormalType;
    private String typeDescription;
    private String abnormalDescription;
    private BigDecimal distanceNm;
    private BigDecimal avgSpeed;
    private BigDecimal maxSpeed;
    private Integer pointCount;
    private String sourceTable;
    private LocalDateTime detectedAt;
    private Map<String, Object> details;
    
    // GeoJSON 형식의 궤적 (선택적)
    private Object trackGeoJson;
}
