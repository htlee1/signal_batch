package gc.mda.signal_batch.domain.passage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "순차 구역 통과 조회 응답")
public class SequentialPassageResponse {
    
    @Schema(description = "총 매칭 선박 수", example = "24")
    private Integer totalVessels;
    
    @Schema(description = "조회 시작 시간", example = "2025-08-01T00:00:00")
    private LocalDateTime startTime;
    
    @Schema(description = "조회 종료 시간", example = "2025-08-07T23:59:59")
    private LocalDateTime endTime;
    
    @Schema(description = "조회 구역 목록", example = "[\"93\", \"92\", \"100\"]")
    private List<String> zones;
    
    @Schema(description = "순차 통과 선박 목록")
    private List<VesselPassage> passages;
    
    @Schema(description = "처리 시간 (ms)", example = "788")
    private Long processingTimeMs;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "선박 통과 정보")
    public static class VesselPassage {
        
        @Schema(description = "신호원 코드", example = "000001")
        private String sigSrcCd;
        
        @Schema(description = "타겟 ID", example = "440308230")
        private String targetId;
        
        @Schema(description = "선박 정보")
        private VesselInfo vesselInfo;
        
        @Schema(description = "구역별 통과 시간")
        private List<ZonePassage> zonePassages;
        
        @Schema(description = "총 이동 거리 (해리)", example = "125.5")
        private Double totalDistance;
        
        @Schema(description = "평균 속도 (knots)", example = "12.3")
        private Double avgSpeed;
    }
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "선박 정보")
    public static class VesselInfo {
        @Schema(description = "선박명", example = "VESSEL NAME")
        private String shipName;
        
        @Schema(description = "선박 타입", example = "30")
        private String shipType;
        
//        @Schema(description = "선박 종류 코드", example = "000020")
//        private String shipKindCode;
//
//        @Schema(description = "IMO 번호", example = "9123456")
//        private String imoNumber;
//
//        @Schema(description = "호출부호", example = "VRAA5")
//        private String callSign;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "구역 통과 정보")
    public static class ZonePassage {
        @Schema(description = "구역 ID", example = "93")
        private String zoneId;
        
        @Schema(description = "구역명", example = "해구 93")
        private String zoneName;
        
        @Schema(description = "진입 시간", example = "2025-08-01T10:30:00")
        private LocalDateTime entryTime;
        
        @Schema(description = "진출 시간", example = "2025-08-01T14:45:00")
        private LocalDateTime exitTime;
        
        @Schema(description = "구역 내 이동거리 (해리)", example = "45.2")
        private Double distanceInZone;
        
        @Schema(description = "구역 내 평균속도 (knots)", example = "11.5")
        private Double avgSpeedInZone;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "구역 통과 통계")
    public static class PassageStatistics {
        @Schema(description = "구역 ID", example = "93")
        private String zoneId;
        
        @Schema(description = "총 통과 선박 수", example = "1722")
        private Integer totalVessels;
        
        @Schema(description = "총 통과 횟수", example = "5431")
        private Integer totalPassages;
        
        @Schema(description = "평균 체류 시간 (분)", example = "245")
        private Double avgDurationMinutes;
        
        @Schema(description = "최다 통과 시간대", example = "14:00-15:00")
        private String peakHour;
    }
}