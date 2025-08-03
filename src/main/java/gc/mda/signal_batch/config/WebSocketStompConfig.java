package gc.mda.signal_batch.config;

import gc.mda.signal_batch.websocket.interceptor.TrackQueryInterceptor;
import gc.mda.signal_batch.websocket.handler.StompErrorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    private final TrackQueryInterceptor trackQueryInterceptor;
    private final ObjectMapper objectMapper; // JacksonConfig에서 생성된 ObjectMapper 주입
    
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(128 * 1024 * 1024); // 128MB
        container.setMaxBinaryMessageBufferSize(128 * 1024 * 1024); // 128MB
        container.setMaxSessionIdleTimeout(60000L);
        return container;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-tracks")
                .setHandshakeHandler(new CustomHandshakeHandler())
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setClientLibraryUrl("/static/libs/js/sockjs.min.js")
                .setStreamBytesLimit(100 * 1024 * 1024)  // 100MB로 증가
                .setHttpMessageCacheSize(1000)
                .setDisconnectDelay(30 * 1000)
                .setWebSocketEnabled(true)
                .setSuppressCors(false);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 인메모리 메시지 브로커 설정
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(messageBrokerTaskScheduler())
                .setHeartbeatValue(new long[]{10000, 10000}); // 서버/클라이언트 하트비트

        // 애플리케이션 destination prefix
        registry.setApplicationDestinationPrefixes("/app");
        
        // 사용자별 destination prefix
        registry.setUserDestinationPrefix("/user");
        
        // 캐시 크기 제한
        registry.setCacheLimit(2048);  // 1024 -> 2048로 증가
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setMessageSizeLimit(20 * 1024 * 1024)      // 20MB로 증가
            .setSendBufferSizeLimit(50 * 1024 * 1024)   // 50MB로 증가
            .setSendTimeLimit(120 * 1000)               // 120초로 증가
            .setTimeToFirstMessage(30 * 1000);         // 첫 메시지까지 30초
        
        // 에러 핸들러 등록은 별도로 처리
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration
            .interceptors(trackQueryInterceptor)
            .taskExecutor()
            .corePoolSize(10)
            .maxPoolSize(20)
            .queueCapacity(100);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration
            .taskExecutor()
            .corePoolSize(20)        // 10 -> 20로 증가
            .maxPoolSize(40)         // 20 -> 40로 증가
            .queueCapacity(5000);    // 1000 -> 5000로 증가
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        // Use the ObjectMapper from JacksonConfig which already has JavaTimeModule configured
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        converter.setPrettyPrint(false);
        
        // Clear default converters and add our custom one
        messageConverters.clear();
        messageConverters.add(converter);
        
        return true; // Don't add default converters
    }

    @Bean
    public TaskScheduler messageBrokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("wsMsgBroker-");
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    // 세션별 고유 ID 생성을 위한 HandshakeHandler
    private static class CustomHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request,
                                        WebSocketHandler wsHandler,
                                        Map<String, Object> attributes) {
            return new StompPrincipal(UUID.randomUUID().toString());
        }
    }
    
    private static class StompPrincipal implements Principal {
        private final String name;
        
        public StompPrincipal(String name) {
            this.name = name;
        }
        
        @Override
        public String getName() {
            return name;
        }
    }
}
