package gc.mda.signal_batch.global.websocket.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 처리된 궤적 데이터 DTO
 * M값이 보정된 궤적 데이터를 포함
 */
@Data
public class ProcessedTrackData {
    private String sigSrcCd;
    private String targetId;
    private LocalDateTime timeBucket;
    private String trackGeom;          // M값 보정된 LineStringM
    private Double distanceNm;
    private Double avgSpeed;
    private Double maxSpeed;
    private Integer chunkIndex;        // 소속 청크 인덱스
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}