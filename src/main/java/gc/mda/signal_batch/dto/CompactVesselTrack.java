package gc.mda.signal_batch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 압축된 선박 궤적 데이터 전송용 DTO
 * LineStringM 대신 단순 배열로 전송하여 프론트엔드 파싱 부하 제거
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactVesselTrack {
    private String vesselId;           // sig_src_cd + "_" + target_id
    private String sigSrcCd;
    private String targetId;
    
    // 궤적 데이터 (배열 형태)
    private List<double[]> geometry;   // [[lon, lat], ...]
    private List<String> timestamps;   // ["2025-07-29 07:05:00", ...]
    private List<Double> speeds;       // [12.5, 13.2, ...]
    
    // 메타데이터
    private Double totalDistance;      // 전체 이동거리 (nm)
    private Double avgSpeed;           // 평균속도 (knots)
    private Double maxSpeed;           // 최대속도 (knots)
    private Integer pointCount;        // 포인트 수
    
    // 선박 정보
    private String shipName;           // 선명
    private String shipType;           // 선종
    private String shipKindCode;       // 선박 종류 코드
}
