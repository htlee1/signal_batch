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
public class VesselData {
    private LocalDateTime messageTime;
    private LocalDateTime realTime;
    private String sigSrcCd;
    private String targetId;
    private Double lat;
    private Double lon;
    private BigDecimal sog;
    private BigDecimal cog;
    private Integer heading;
    private String shipNm;
    private String shipTy;
    private Integer rot;
    private Integer posacc;
    private String sensorId;
    private String baseStId;
    private Integer mode;
    private Integer gpsSttus;
    private Integer batterySttus;
    private String vtsCd;
    private String mmsi;
    private String vpassId;
    private String shipNo;

    public String getVesselKey() {
        return sigSrcCd + "_" + targetId;
    }

    public boolean isValidPosition() {
        return lat != null && lon != null &&
                lat >= -90 && lat <= 90 &&
                lon >= -180 && lon <= 180;
    }
}