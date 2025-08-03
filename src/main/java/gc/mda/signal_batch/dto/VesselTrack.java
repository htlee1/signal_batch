//package gc.mda.signal_batch.dto;
//
//import lombok.Builder;
//import lombok.Data;
//import org.locationtech.jts.geom.LineString;
//
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//
///**
// * 비정상 궤적 처리를 위한 DTO
// */
//@Data
//@Builder
//public class VesselTrack {
//    private String sigSrcCd;
//    private String targetId;
//    private LocalDateTime timeBucket;
//    private LineString trackGeom;
//    private BigDecimal distanceNm;
//    private BigDecimal avgSpeed;
//    private BigDecimal maxSpeed;
//    private Integer pointCount;
//    private Integer haeguNo;
//
//    public String getVesselId() {
//        return sigSrcCd + ":" + targetId;
//    }
//}
