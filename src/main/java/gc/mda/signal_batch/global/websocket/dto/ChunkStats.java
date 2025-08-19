package gc.mda.signal_batch.global.websocket.dto;

import lombok.Data;

@Data
public class ChunkStats {
    private Integer processedTracks;
    private Integer totalTracks;
    private Integer trackCount;       // 현재 청크의 트랙 수
    private Double progressPercentage;
    private Long elapsedMillis;
    private Double chunkSizeKB;        // 청크 크기 (KB)
    
    // 진행률 추가 정보
    private Integer processedVessels;  // 누적 처리된 선박 수
    private Integer estimatedTotalVessels;  // 예상 총 선박 수
    private Integer processedMinutes;  // 처리된 시간 범위 (분)
    private Integer totalMinutes;      // 전체 시간 범위 (분)
    private Double vesselProgress;     // 선박 기반 진행률
    private Double timeProgress;       // 시간 기반 진행률
    private Double combinedProgress;   // 가중 평균 진행률
}