package gc.mda.signal_batch.global.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 선박별로 병합된 궤적 데이터
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergedVesselTrack {
    private String sigSrcCd;
    private String targetId;
    private String vesselId; // sigSrcCd_targetId
    private String mergedTrackGeom; // 병합된 전체 궤적 (LineStringM)
    private Double totalDistanceNm;
    private Double avgSpeed;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer totalPoints;
    private List<String> timeBuckets; // 포함된 시간 세그먼트 목록
}