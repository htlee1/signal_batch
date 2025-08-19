package gc.mda.signal_batch.domain.gis.model;


import gc.mda.signal_batch.domain.vessel.model.VesselData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TileStatistics implements java.io.Serializable {
    private String tileId;
    private Integer tileLevel;
    private LocalDateTime timeBucket;
    private Integer vesselCount;
    private Map<String, VesselInfo> uniqueVessels;
    private Long totalPoints;
    private BigDecimal avgSog;
    private BigDecimal maxSog;
    private BigDecimal vesselDensity;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VesselInfo implements java.io.Serializable {
        private Double lat;
        private Double lon;
        private BigDecimal sog;
        private LocalDateTime lastSeen;
    }

    public TileStatistics(String tileId, Integer level, LocalDateTime timeBucket) {
        this.tileId = tileId;
        this.tileLevel = level;
        this.timeBucket = timeBucket;
        this.uniqueVessels = new HashMap<>();
        this.totalPoints = 0L;
        this.avgSog = BigDecimal.ZERO;
        this.maxSog = BigDecimal.ZERO;
    }

    public void addVesselData(VesselData data) {
        String vesselKey = data.getVesselKey();

        VesselInfo info = VesselInfo.builder()
                .lat(data.getLat())
                .lon(data.getLon())
                .sog(data.getSog())
                .lastSeen(data.getMessageTime())
                .build();

        uniqueVessels.put(vesselKey, info);
        // totalPoints 제거 - 최신 위치만 사용

        // Update statistics
        if (data.getSog() != null) {
            if (maxSog == null || data.getSog().compareTo(maxSog) > 0) {
                maxSog = data.getSog();
            }
        }

        vesselCount = uniqueVessels.size();

        // 평균 속도는 모든 선박의 현재 속도 평균
        BigDecimal totalSog = BigDecimal.ZERO;
        int sogCount = 0;
        for (VesselInfo vessel : uniqueVessels.values()) {
            if (vessel.getSog() != null) {
                totalSog = totalSog.add(vessel.getSog());
                sogCount++;
            }
        }
        if (sogCount > 0) {
            avgSog = totalSog.divide(BigDecimal.valueOf(sogCount), 2, BigDecimal.ROUND_HALF_UP);
        }
    }

    /**
     * unique_vessels를 JSON 문자열로 변환
     */
    public String getUniqueVesselsJson() {
        if (uniqueVessels == null || uniqueVessels.isEmpty()) {
            return "{}";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(uniqueVessels);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert unique vessels to JSON", e);
            return "{}";
        }
    }

    /**
     * 타일 통계 병합 (대해구 집계용)
     */
    public void merge(TileStatistics other) {
        if (other == null) return;

        // unique vessels 병합
        if (other.uniqueVessels != null) {
            this.uniqueVessels.putAll(other.uniqueVessels);
        }

        // vessel count 업데이트
        this.vesselCount = this.uniqueVessels.size();

        // total points 합산
        this.totalPoints += other.totalPoints;

        // max sog 업데이트
        if (other.maxSog != null && (this.maxSog == null || other.maxSog.compareTo(this.maxSog) > 0)) {
            this.maxSog = other.maxSog;
        }

        // average sog 재계산 (가중 평균)
        if (this.totalPoints > 0 && other.avgSog != null) {
            BigDecimal thisWeight = BigDecimal.valueOf(this.totalPoints - other.totalPoints);
            BigDecimal otherWeight = BigDecimal.valueOf(other.totalPoints);

            BigDecimal weightedSum = this.avgSog.multiply(thisWeight)
                    .add(other.avgSog.multiply(otherWeight));

            this.avgSog = weightedSum.divide(BigDecimal.valueOf(this.totalPoints), 2, BigDecimal.ROUND_HALF_UP);
        }
    }
}