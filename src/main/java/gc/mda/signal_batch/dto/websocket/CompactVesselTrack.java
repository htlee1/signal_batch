package gc.mda.signal_batch.dto.websocket;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 병합된 선박 궤적 데이터
 * LineStringM 대신 배열 형태로 전송
 */
@Data
@Builder
public class CompactVesselTrack {
    private String vesselId;          // sig_src_cd_target_id
    private String sigSrcCd;
    private String targetId;
    private Integer chunkIndex;
    
    // 2D 좌표 배열 [[lon1,lat1], [lon2,lat2], ...]
    private List<double[]> geometry;
    
    // 각 포인트의 시간 (YYYY-MM-dd HH:mm:ss)
    private List<String> timestamps;
    
    // 속도 배열 (knots)
    private List<Double> speeds;
    
    // 선택적 필드
    private Double totalDistance;
    private Double avgSpeed;
    private Double maxSpeed;
    private Integer pointCount;
}
