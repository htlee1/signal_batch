package gc.mda.signal_batch.controller.websocket;

import gc.mda.signal_batch.dto.websocket.*;
import gc.mda.signal_batch.service.StompTrackStreamingService;
import gc.mda.signal_batch.service.ChunkedTrackStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Controller
@RequiredArgsConstructor
@Validated
public class StompTrackController {

    private final StompTrackStreamingService trackStreamingService;
    private final ChunkedTrackStreamingService chunkedTrackStreamingService;
    private final SimpMessagingTemplate messagingTemplate;
    
    // 세션별 활성 쿼리 관리
    private final Map<String, QuerySession> activeSessions = new ConcurrentHashMap<>();

    /**
     * 궤적 쿼리 시작
     * STOMP destination: /app/tracks/query
     */
    @MessageMapping("/tracks/query")
    @SendToUser("/queue/tracks/response")
    public QueryResponse startTrackQuery(
            @Payload @Valid TrackQueryRequest request,
            @Header("simpSessionId") String sessionId,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {
        
        String queryId = UUID.randomUUID().toString();
        String userId = principal.getName();
        
        log.info("Starting track query - ID: {}, Session: {}, User: {}", 
                queryId, sessionId, userId);
        log.info("Request info - {}", request);
        
        // 세션 정보 저장
        QuerySession session = new QuerySession(queryId, sessionId, userId);
        activeSessions.put(sessionId, session);
        
        // 헤더에 추가 정보 설정
        headerAccessor.setHeader("queryId", queryId);
        headerAccessor.setLeaveMutable(true);
        
        // 비동기 스트리밍 시작 - 청크 모드 체크
        if (request.isChunkedMode()) {
            // 새로운 청크 스트리밍 모드
            chunkedTrackStreamingService.streamChunkedTracks(
                request, 
                queryId,
                chunk -> sendChunkedDataToUser(userId, chunk),
                status -> sendStatusToUser(userId, status)
            );
        } else {
            // 기존 스트리밍 모드
            trackStreamingService.streamTracks(
                request, 
                queryId, 
                sessionId,
                chunk -> sendChunkToUser(userId, chunk),
                status -> sendStatusToUser(userId, status)
            );
        }
        
        // 즉시 응답
        return QueryResponse.started(queryId);
    }

    /**
     * 진행 중인 쿼리 취소
     * STOMP destination: /app/tracks/cancel/{queryId}
     */
    @MessageMapping("/tracks/cancel/{queryId}")
    @SendToUser("/queue/tracks/response")
    public QueryResponse cancelQuery(
            @DestinationVariable String queryId,
            @Header("simpSessionId") String sessionId,
            Principal principal) {
        
        log.info("Cancelling query - ID: {}, Session: {}", queryId, sessionId);
        
        QuerySession session = activeSessions.get(sessionId);
        if (session != null && session.getQueryId().equals(queryId)) {
            // 두 서비스 모두에 취소 요청
            trackStreamingService.cancelQuery(queryId);
            chunkedTrackStreamingService.cancelQuery(queryId);
            activeSessions.remove(sessionId);
            return QueryResponse.cancelled(queryId);
        }
        
        return QueryResponse.error(queryId, "Query not found");
    }

    /**
     * 하트비트 처리
     * STOMP destination: /app/heartbeat
     */
    @MessageMapping("/heartbeat")
    public void handleHeartbeat(@Header("simpSessionId") String sessionId) {
        QuerySession session = activeSessions.get(sessionId);
        if (session != null) {
            session.updateLastActivity();
        }
    }

    // Private helper methods
    private void sendChunkToUser(String userId, TrackChunkResponse chunk) {
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/tracks/data", 
            chunk,
            createHeaders(chunk.getQueryId(), "CHUNK", chunk.getChunkIndex())
        );
    }

    private void sendStatusToUser(String userId, QueryStatusUpdate status) {
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/tracks/status", 
            status,
            createHeaders(status.getQueryId(), "STATUS", null)
        );
    }
    
    private void sendChunkedDataToUser(String userId, TrackChunkResponse chunk) {
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/tracks/chunk", 
            chunk,
            createHeaders(chunk.getQueryId(), "CHUNKED_DATA", chunk.getChunkIndex())
        );
    }

    private Map<String, Object> createHeaders(String queryId, String messageType, Integer chunkIndex) {
        Map<String, Object> headers = new ConcurrentHashMap<>();
        headers.put("queryId", queryId);
        headers.put("messageType", messageType);
        headers.put("timestamp", System.currentTimeMillis());
        if (chunkIndex != null) {
            headers.put("chunkIndex", chunkIndex);
        }
        return headers;
    }

    // Inner class for session management
    private static class QuerySession {
        private final String queryId;
        private final String sessionId;
        private final String userId;
        private final LocalDateTime connectedAt;
        private LocalDateTime lastActivity;

        public QuerySession(String queryId, String sessionId, String userId) {
            this.queryId = queryId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.connectedAt = LocalDateTime.now();
            this.lastActivity = LocalDateTime.now();
        }

        public void updateLastActivity() {
            this.lastActivity = LocalDateTime.now();
        }

        // Getters
        public String getQueryId() {
            return queryId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getUserId() {
            return userId;
        }

        public LocalDateTime getConnectedAt() {
            return connectedAt;
        }

        public LocalDateTime getLastActivity() {
            return lastActivity;
        }
    }
}
