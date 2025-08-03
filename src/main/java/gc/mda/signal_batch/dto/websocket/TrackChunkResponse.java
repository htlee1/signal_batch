package gc.mda.signal_batch.dto.websocket;

import gc.mda.signal_batch.dto.CompactVesselTrack;
import lombok.Data;
import java.util.List;

@Data
public class TrackChunkResponse {
    private String queryId;
    private Integer chunkIndex;
    private Integer totalChunks;
    private List<VesselTrackData> tracks;
    private List<MergedVesselTrack> mergedTracks; // 병합된 선박 궤적
    private List<CompactVesselTrack> compactTracks; // 압축된 선박 궤적
    private ChunkStats stats;
    private Boolean isLastChunk;
    private Boolean isMerged = false; // 병합 여부
}
