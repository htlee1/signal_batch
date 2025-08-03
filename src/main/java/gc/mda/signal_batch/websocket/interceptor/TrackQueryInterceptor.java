package gc.mda.signal_batch.websocket.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackQueryInterceptor implements ChannelInterceptor {

    // 세션별 활성 쿼리 수 추적
    private final ConcurrentHashMap<String, AtomicInteger> sessionQueries = new ConcurrentHashMap<>();
    private static final int MAX_QUERIES_PER_SESSION = 3;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        
        if (accessor != null && StompCommand.SEND.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            String sessionId = accessor.getSessionId();
            
            // 궤적 쿼리 요청 확인
            if ("/app/tracks/query".equals(destination)) {
                AtomicInteger queryCount = sessionQueries.computeIfAbsent(
                    sessionId, k -> new AtomicInteger(0)
                );
                
                if (queryCount.get() >= MAX_QUERIES_PER_SESSION) {
                    log.warn("Session {} exceeded max queries limit", sessionId);
                    throw new IllegalStateException("Maximum concurrent queries exceeded");
                }
                
                queryCount.incrementAndGet();
                log.info("Session {} started query. Active queries: {}", 
                        sessionId, queryCount.get());
            }
        }
        
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, 
                                   boolean sent, Exception ex) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
            message, StompHeaderAccessor.class
        );
        
        if (accessor != null && StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            String sessionId = accessor.getSessionId();
            sessionQueries.remove(sessionId);
            log.info("Session {} disconnected, cleared query count", sessionId);
        }
    }
    
    public void decrementQueryCount(String sessionId) {
        AtomicInteger count = sessionQueries.get(sessionId);
        if (count != null) {
            count.decrementAndGet();
        }
    }
}
