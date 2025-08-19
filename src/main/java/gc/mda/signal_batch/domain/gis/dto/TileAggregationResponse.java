package gc.mda.signal_batch.domain.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "타일 집계 응답")
public class TileAggregationResponse {
    
    @Schema(description = "요청 정보")
    private RequestInfo request;
    
    @Schema(description = "집계 요약")
    private AggregationSummary summary;
    
    @Schema(description = "타일별 상세 데이터")
    private List<TileDetail> tiles;
    
    @Schema(description = "응답 생성 시간")
    private LocalDateTime responseTime;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "요청 정보")
    public static class RequestInfo {
        @Schema(description = "조회 시작 시간")
        private LocalDateTime fromDate;
        
        @Schema(description = "조회 종료 시간")
        private LocalDateTime toDate;
        
        @Schema(description = "조회 타일 ID (없으면 전체)")
        private String tileId;
        
        @Schema(description = "타일 레벨 (0: 대해구, 1: 소해구)")
        private Integer tileLevel;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "집계 요약")
    public static class AggregationSummary {
        @Schema(description = "전체 타일 수")
        private Integer totalTiles;
        
        @Schema(description = "전체 고유 선박 수")
        private Integer totalUniqueVessels;
        
        @Schema(description = "조회된 타임버킷 수")
        private Integer timeBuckets;
        
        @Schema(description = "평균 선박 밀도")
        private Double avgVesselDensity;
        
        @Schema(description = "최대 선박 수를 가진 타일 ID")
        private String maxVesselTileId;
        
        @Schema(description = "최대 선박 수")
        private Integer maxVesselCount;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "타일 상세 정보")
    public static class TileDetail {
        @Schema(description = "타일 ID", example = "H238")
        private String tileId;
        
        @Schema(description = "타일 레벨 (0: 대해구, 1: 소해구)")
        private Integer tileLevel;
        
        @Schema(description = "대해구 번호")
        private Integer haeguNo;
        
        @Schema(description = "소해구 번호 (소해구인 경우)")
        private Integer sohaeguNo;
        
        @Schema(description = "고유 선박 수")
        private Integer vesselCount;
        
        @Schema(description = "평균 선박 밀도 (선박수/km²)")
        private Double avgDensity;
        
        @Schema(description = "선박별 상세 정보", example = """
            {
              "000001_413409000": {
                "lat": 38.507038,
                "lon": 121.69615,
                "sog": 15.3,
                "lastSeen": "2025-01-18T15:15:01"
              }
            }
            """)
        private Map<String, VesselInfo> uniqueVessels;
        
        @Schema(description = "타일 경계 정보")
        private TileBoundary boundary;
        
        @Schema(description = "하위 타일 정보 (대해구인 경우)")
        private List<TileDetail> subTiles;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "선박 정보")
    public static class VesselInfo {
        @Schema(description = "위도", example = "35.123456")
        private Double lat;
        
        @Schema(description = "경도", example = "129.123456")
        private Double lon;
        
        @Schema(description = "속도 (knots)", example = "12.5")
        private Double sog;
        
        @Schema(description = "마지막 확인 시간")
        private LocalDateTime lastSeen;
        
        @Schema(description = "선박명 (있는 경우)")
        private String shipName;
        
        @Schema(description = "MMSI (있는 경우)")
        private String mmsi;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "타일 경계 정보")
    public static class TileBoundary {
        @Schema(description = "최소 위도")
        private Double minLat;
        
        @Schema(description = "최소 경도")
        private Double minLon;
        
        @Schema(description = "최대 위도")
        private Double maxLat;
        
        @Schema(description = "최대 경도")
        private Double maxLon;
        
        @Schema(description = "중심점 위도")
        private Double centerLat;
        
        @Schema(description = "중심점 경도")
        private Double centerLon;
        
        @Schema(description = "면적 (km²)")
        private Double area;
    }
}