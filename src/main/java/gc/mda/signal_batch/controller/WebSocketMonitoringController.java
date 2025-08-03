package gc.mda.signal_batch.controller;

import gc.mda.signal_batch.monitoring.TrackStreamingMetrics;
import gc.mda.signal_batch.service.StompTrackStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/websocket")
@RequiredArgsConstructor
public class WebSocketMonitoringController {

    private final TrackStreamingMetrics trackStreamingMetrics;
    private final StompTrackStreamingService trackStreamingService;

    /**
     * WebSocket 스트리밍 현황 조회
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStreamingStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // 활성 쿼리 수
        status.put("activeQueries", trackStreamingMetrics.getActiveQueryCount());
        
        // 메모리 사용량
        trackStreamingMetrics.recordMemoryUsage();
        Runtime runtime = Runtime.getRuntime();
        Map<String, Long> memory = new HashMap<>();
        memory.put("used", runtime.totalMemory() - runtime.freeMemory());
        memory.put("total", runtime.totalMemory());
        memory.put("max", runtime.maxMemory());
        status.put("memory", memory);
        
        // 서버 시간
        status.put("serverTime", System.currentTimeMillis());
        
        return ResponseEntity.ok(status);
    }

    /**
     * 특정 쿼리의 상태 조회
     */
    @GetMapping("/query/{queryId}/status")
    public ResponseEntity<Map<String, Object>> getQueryStatus(@PathVariable String queryId) {
        Map<String, Object> result = new HashMap<>();
        
        var queryStatus = trackStreamingService.getQueryStatus(queryId);
        result.put("status", queryStatus.getStatus());
        result.put("message", queryStatus.getMessage());
        result.put("progress", queryStatus.getProgressPercentage());
        result.put("tracksStreamed", trackStreamingMetrics.getTracksStreamedForQuery(queryId));
        
        return ResponseEntity.ok(result);
    }

    /**
     * WebSocket 테스트 페이지로 리다이렉트
     */
    @GetMapping("/test")
    public RedirectView redirectToTestPage() {
        return new RedirectView("/websocket/track-streaming-test.html");
    }
}
