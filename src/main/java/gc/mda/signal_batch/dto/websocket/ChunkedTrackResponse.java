package gc.mda.signal_batch.dto.websocket;

import gc.mda.signal_batch.dto.CompactVesselTrack;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * 청크 기반 궤적 스트리밍 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkedTrackResponse {
    private String queryId;
    private Integer chunkIndex;        // 청크 순서 (0부터)
    private Integer totalChunks;       // 전체 청크 수
    private TimeRange timeRange;       // 청크 시간 범위
    private List<ProcessedTrackData> tracks;
    private List<CompactVesselTrack> compactTracks;  // 압축된 형태의 트랙
    private Boolean isLastChunk;
    private ChunkStats stats;
}
