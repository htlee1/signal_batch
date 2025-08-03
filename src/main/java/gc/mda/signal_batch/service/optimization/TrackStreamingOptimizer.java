package gc.mda.signal_batch.service.optimization;

import gc.mda.signal_batch.dto.websocket.TrackChunkResponse;
import gc.mda.signal_batch.dto.websocket.VesselTrackData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TrackStreamingOptimizer {
    
    // 시간 범위별 최적 청크 크기
    private static final Map<Integer, Integer> OPTIMAL_CHUNK_SIZES = Map.of(
        1, 2000,    // 1일 이내
        7, 1000,    // 7일 이내
        30, 500,    // 30일 이내
        90, 250     // 90일 이내
    );
    
    // 병렬 처리 스레드 수
    private static final Map<Integer, Integer> PARALLEL_THREADS = Map.of(
        1, 2,       // 1일 이내
        7, 4,       // 7일 이내
        30, 8,      // 30일 이내
        90, 12      // 90일 이내
    );
    
    /**
     * 조회 기간에 따른 최적 청크 크기 계산
     */
    public int calculateOptimalChunkSize(long durationDays, int requestedChunkSize) {
        if (requestedChunkSize > 0) {
            return requestedChunkSize;
        }
        
        return OPTIMAL_CHUNK_SIZES.entrySet().stream()
            .filter(entry -> durationDays <= entry.getKey())
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(100);
    }
    
    /**
     * 병렬 처리 스레드 수 계산
     */
    public int calculateParallelThreads(long durationDays) {
        return PARALLEL_THREADS.entrySet().stream()
            .filter(entry -> durationDays <= entry.getKey())
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(16);
    }
    
    /**
     * 트랙 데이터 우선순위 정렬
     * - 속도가 높은 선박 우선
     * - 이동 거리가 긴 선박 우선
     * - 최근 데이터 우선
     */
    public List<VesselTrackData> prioritizeTracks(List<VesselTrackData> tracks) {
        return tracks.stream()
            .sorted(Comparator
                .comparing(VesselTrackData::getAvgSpeed, Comparator.reverseOrder())
                .thenComparing(VesselTrackData::getDistanceNm, Comparator.reverseOrder())
                .thenComparing(VesselTrackData::getEndTime, Comparator.reverseOrder()))
            .collect(Collectors.toList());
    }
    
    /**
     * 적응형 청크 생성
     * - 네트워크 상태에 따라 청크 크기 동적 조정
     * - 클라이언트 처리 속도에 따라 백프레셔 적용
     */
    public CompletableFuture<TrackChunkResponse> createAdaptiveChunk(
            String queryId,
            List<VesselTrackData> tracks,
            int chunkIndex,
            int baseChunkSize,
            NetworkMetrics networkMetrics) {
        
        return CompletableFuture.supplyAsync(() -> {
            // 네트워크 지연에 따른 청크 크기 조정
            int adjustedChunkSize = adjustChunkSize(baseChunkSize, networkMetrics);
            
            // 우선순위에 따라 정렬된 트랙 선택
            List<VesselTrackData> prioritizedTracks = prioritizeTracks(tracks);
            List<VesselTrackData> chunkTracks = prioritizedTracks.stream()
                .limit(adjustedChunkSize)
                .collect(Collectors.toList());
            
            TrackChunkResponse chunk = new TrackChunkResponse();
            chunk.setQueryId(queryId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setTracks(chunkTracks);
            chunk.setIsLastChunk(false);
            
            log.debug("Created adaptive chunk {} with {} tracks (base: {}, adjusted: {})",
                     chunkIndex, chunkTracks.size(), baseChunkSize, adjustedChunkSize);
            
            return chunk;
        });
    }
    
    /**
     * 백프레셔 적용을 위한 청크 크기 조정
     */
    private int adjustChunkSize(int baseSize, NetworkMetrics metrics) {
        double latencyFactor = Math.min(1.0, 100.0 / metrics.getAverageLatencyMs());
        double throughputFactor = Math.min(1.0, metrics.getThroughputKbps() / 1000.0);
        
        double adjustmentFactor = (latencyFactor + throughputFactor) / 2.0;
        return Math.max(100, (int)(baseSize * adjustmentFactor));
    }
    
    /**
     * 스마트 캐싱 전략
     * - 자주 조회되는 영역의 트랙 데이터 캐싱
     * - TTL 기반 캐시 무효화
     */
    public class SmartCache {
        private final Map<String, CachedData> cache = new ConcurrentHashMap<>();
        private final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);
        
        public Optional<List<VesselTrackData>> get(String cacheKey) {
            CachedData cached = cache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return Optional.of(cached.getData());
            }
            cache.remove(cacheKey);
            return Optional.empty();
        }
        
        public void put(String cacheKey, List<VesselTrackData> data) {
            cache.put(cacheKey, new CachedData(data));
        }
        
        private class CachedData {
            private final List<VesselTrackData> data;
            private final long timestamp;
            
            CachedData(List<VesselTrackData> data) {
                this.data = data;
                this.timestamp = System.currentTimeMillis();
            }
            
            boolean isExpired() {
                return System.currentTimeMillis() - timestamp > TTL_MILLIS;
            }
            
            List<VesselTrackData> getData() {
                return data;
            }
        }
    }
    
    /**
     * 네트워크 메트릭
     */
    public static class NetworkMetrics {
        private final Queue<Long> latencies = new ConcurrentLinkedQueue<>();
        private final Queue<Double> throughputs = new ConcurrentLinkedQueue<>();
        private static final int MAX_SAMPLES = 100;
        
        public void recordLatency(long latencyMs) {
            latencies.offer(latencyMs);
            if (latencies.size() > MAX_SAMPLES) {
                latencies.poll();
            }
        }
        
        public void recordThroughput(double throughputKbps) {
            throughputs.offer(throughputKbps);
            if (throughputs.size() > MAX_SAMPLES) {
                throughputs.poll();
            }
        }
        
        public double getAverageLatencyMs() {
            return latencies.isEmpty() ? 50.0 : 
                   latencies.stream().mapToLong(Long::longValue).average().orElse(50.0);
        }
        
        public double getThroughputKbps() {
            return throughputs.isEmpty() ? 1000.0 :
                   throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(1000.0);
        }
    }
}