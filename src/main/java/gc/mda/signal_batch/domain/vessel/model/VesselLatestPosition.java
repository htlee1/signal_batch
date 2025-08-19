package gc.mda.signal_batch.domain.vessel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VesselLatestPosition {
    private String sigSrcCd;
    private String targetId;
    private Double lat;
    private Double lon;
    private String geomWkt;
    private BigDecimal sog;
    private BigDecimal cog;
    private Integer heading;
    private String shipNm;
    private String shipTy;
    private LocalDateTime lastUpdate;
    private Long updateCount;
    private LocalDateTime createdAt;

    public static VesselLatestPosition fromVesselData(VesselData data) {
        return VesselLatestPosition.builder()
                .sigSrcCd(data.getSigSrcCd())
                .targetId(data.getTargetId())
                .lat(data.getLat())
                .lon(data.getLon())
                .geomWkt(String.format("POINT(%f %f)", data.getLon(), data.getLat()))
                .sog(data.getSog())
                .cog(data.getCog())
                .heading(data.getHeading())
                .shipNm(data.getShipNm())
                .shipTy(data.getShipTy())
                .lastUpdate(data.getMessageTime())
                .updateCount(1L)
                .build();
    }
}