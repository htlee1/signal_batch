package gc.mda.signal_batch.global.websocket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TrackQueryRequest {
    @NotNull(message = "Start time is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startTime;
    
    @NotNull(message = "End time is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endTime;
    
    // 필터 옵션
    private ViewportFilter viewport;
    private List<Integer> haeguNumbers;
    private List<String> areaIds;
    private List<String> vesselIds;
    
    // 거리/속도 기반 필터
    @DecimalMin(value = "0.0", inclusive = true)
    private Double minTotalDistance;  // 최소 전체 이동거리 (nm)
    
    @DecimalMin(value = "0.0", inclusive = true)
    private Double maxTotalDistance;  // 최대 전체 이동거리 (nm)
    
    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "50.0", inclusive = true)
    private Double minAvgSpeed;  // 최소 평균속도 (knots)
    
    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "50.0", inclusive = true)
    private Double maxAvgSpeed;  // 최대 평균속도 (knots)
    
    // 청크 스트리밍 모드
    @Builder.Default
    private boolean chunkedMode = false;  // true: 시간 기반 청크 분할
    
    @Builder.Default
    private Boolean includeInterBucketDistance = true;  // bucket 간 거리 포함 여부
    
    // 성능 옵션
    @Min(100)
    @Max(20000) // 최대 20,000으로 증가
    @Builder.Default
    private Integer chunkSize = 1000;
    
    @Builder.Default
    private Boolean includeStats = true;
    
    @DecimalMin(value = "0.0", inclusive = true)
    @DecimalMax(value = "1.0", inclusive = true)
    private Double simplificationTolerance;
    
    // STOMP 관련 옵션
    @Builder.Default
    private Boolean compressResponse = true;
    
    @Builder.Default
    private String responseFormat = "JSON"; // JSON, BINARY, PROTOBUF
    
    @Builder.Default
    private Integer priority = 5; // 1-10, 높을수록 우선

    /**
     * 트랙당 최대 포인트 수
     * 이 값을 초과하면 자동 간소화
     */
    private Integer maxPointsPerTrack;

    /**
     * 응답 최대 크기 (KB)
     * 전체 응답이 이 크기를 초과하지 않도록 조정
     */
    private Integer maxResponseSizeKB;

    /**
     * 맵 줌 레벨 (0-22)
     * 줌 레벨에 따라 간소화 정도를 자동 조정
     * 0-5: 극도 간소화 (90% 제거)
     * 6-7: 매우 강한 간소화 (80% 제거)
     * 8-9: 강한 간소화 (70% 제거)
     * 10-11: 중간 간소화 (50% 제거)
     * 12+: 경량 간소화 (30% 제거)
     */
    @Min(0)
    @Max(22)
    private Integer zoomLevel;

    /**
     * 간소화 모드
     */
    @Builder.Default
    private SimplificationMode simplificationMode = SimplificationMode.AUTO;

    public enum SimplificationMode {
        AUTO,       // 자동 결정 (기본값)
        NONE,       // 간소화 없음
        ADAPTIVE,   // 데이터 밀도에 따라 적응
        AGGRESSIVE  // 적극적 간소화
    }

    /**
     * 동적 품질 조정 활성화
     * true인 경우 네트워크 상태에 따라 품질 자동 조정
     */
    @Builder.Default
    private Boolean enableDynamicQuality = false;

    /**
     * 시각적 중요도 기반 간소화
     * true인 경우 방향 전환점, 정지점 등을 보존
     */
    @Builder.Default
    private Boolean preserveKeyPoints = true;

    // 빌더 패턴으로 편리한 사용
    public static TrackQueryRequestBuilder forTimeRange(LocalDateTime start, LocalDateTime end) {
        return TrackQueryRequest.builder()
                .startTime(start)
                .endTime(end);
    }

    // 간소화 프리셋
    public TrackQueryRequest withHighQuality() {
        this.simplificationMode = SimplificationMode.NONE;
        this.maxPointsPerTrack = null;
        return this;
    }

    public TrackQueryRequest withBalancedQuality() {
        this.simplificationMode = SimplificationMode.AUTO;
        this.maxPointsPerTrack = 1000;
        this.maxResponseSizeKB = 5000;
        return this;
    }

    public TrackQueryRequest withLowBandwidth() {
        this.simplificationMode = SimplificationMode.AGGRESSIVE;
        this.maxPointsPerTrack = 100;
        this.maxResponseSizeKB = 1000;
        return this;
    }
}