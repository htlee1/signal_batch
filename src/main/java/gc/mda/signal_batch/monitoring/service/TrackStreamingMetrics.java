package gc.mda.signal_batch.monitoring.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@Component
public class TrackStreamingMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> activeQueries = new ConcurrentHashMap<>();
    
    // 메트릭 카운터
    private final Counter queryStartedCounter;
    private final Counter queryCompletedCounter;
    private final Counter queryCancelledCounter;
    private final Counter queryErrorCounter;
    private final Counter tracksStreamedCounter;
    private final Timer chunkProcessingTimer;
    private final Timer queryExecutionTimer;

    public TrackStreamingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 카운터 초기화
        this.queryStartedCounter = Counter.builder("track.query.started")
            .description("Number of track queries started")
            .register(meterRegistry);
            
        this.queryCompletedCounter = Counter.builder("track.query.completed")
            .description("Number of track queries completed")
            .register(meterRegistry);
            
        this.queryCancelledCounter = Counter.builder("track.query.cancelled")
            .description("Number of track queries cancelled")
            .register(meterRegistry);
            
        this.queryErrorCounter = Counter.builder("track.query.error")
            .description("Number of track queries failed")
            .register(meterRegistry);
            
        this.tracksStreamedCounter = Counter.builder("track.streamed.count")
            .description("Total number of tracks streamed")
            .register(meterRegistry);
            
        this.chunkProcessingTimer = Timer.builder("track.chunk.processing")
            .description("Time to process track chunks")
            .register(meterRegistry);
            
        this.queryExecutionTimer = Timer.builder("track.query.execution")
            .description("Total query execution time")
            .register(meterRegistry);
        
        // 활성 쿼리 게이지
        Gauge.builder("track.query.active", activeQueries, Map::size)
            .description("Number of active track queries")
            .register(meterRegistry);
            
        // WebSocket 연결 메트릭
        Gauge.builder("websocket.sessions.active", () -> getActiveWebSocketSessions())
            .description("Number of active WebSocket sessions")
            .register(meterRegistry);
    }

    public void recordQueryStarted(String queryId, String queryType) {
        queryStartedCounter.increment();
        activeQueries.put(queryId, new AtomicInteger(0));
        
        meterRegistry.counter("track.query.started.byType", "type", queryType).increment();
        log.debug("Query started - ID: {}, Type: {}", queryId, queryType);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordQueryCompleted(String queryId, Timer.Sample sample) {
        queryCompletedCounter.increment();
        activeQueries.remove(queryId);
        
        if (sample != null) {
            sample.stop(queryExecutionTimer);
        }
        
        log.debug("Query completed - ID: {}", queryId);
    }

    public void recordQueryCancelled(String queryId) {
        queryCancelledCounter.increment();
        activeQueries.remove(queryId);
        log.debug("Query cancelled - ID: {}", queryId);
    }

    public void recordQueryError(String queryId, String errorType) {
        queryErrorCounter.increment();
        activeQueries.remove(queryId);
        
        meterRegistry.counter("track.query.error.byType", "type", errorType).increment();
        log.debug("Query error - ID: {}, Type: {}", queryId, errorType);
    }

    public void recordChunkProcessed(String queryId, int trackCount, long processingTimeMs) {
        tracksStreamedCounter.increment(trackCount);
        chunkProcessingTimer.record(processingTimeMs, TimeUnit.MILLISECONDS);
        
        AtomicInteger queryTracks = activeQueries.get(queryId);
        if (queryTracks != null) {
            queryTracks.addAndGet(trackCount);
        }
        
        meterRegistry.counter("track.chunk.tracks", "queryId", queryId).increment(trackCount);
    }

    public void recordMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        
        meterRegistry.gauge("track.streaming.memory.used", usedMemory);
        meterRegistry.gauge("track.streaming.memory.max", runtime.maxMemory());
    }
    
    // WebSocket 세션 카운트 (실제 구현 시 WebSocketRegistry와 연동 필요)
    private int getActiveWebSocketSessions() {
        // TODO: WebSocket 세션 레지스트리와 연동
        return activeQueries.size(); // 임시로 활성 쿼리 수 반환
    }
    
    public int getActiveQueryCount() {
        return activeQueries.size();
    }
    
    public int getTracksStreamedForQuery(String queryId) {
        AtomicInteger count = activeQueries.get(queryId);
        return count != null ? count.get() : 0;
    }
}