package gc.mda.signal_batch.websocket;

import gc.mda.signal_batch.dto.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WebSocketLoadTest {
    
    private static final String WS_URL = "ws://10.26.252.48:8090/ws-tracks";
    private static final int CONCURRENT_CLIENTS = 10;
    private static final int QUERIES_PER_CLIENT = 5;
    
    private WebSocketStompClient stompClient;
    private final List<TestClient> testClients = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
    
    @BeforeEach
    void setUp() {
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.setDefaultHeartbeat(new long[]{10000, 10000});
    }
    
    @AfterEach
    void tearDown() {
        testClients.forEach(TestClient::disconnect);
        executor.shutdown();
    }
    
    @Test
    @Order(1)
    @DisplayName("단일 클라이언트 연결 테스트")
    void testSingleClientConnection() throws Exception {
        TestClient client = new TestClient("test-client-1");
        assertTrue(client.connect(), "클라이언트 연결 실패");
        
        // 간단한 쿼리 전송
        TrackQueryRequest request = createTestQuery(1);
        client.sendQuery(request);
        
        // 응답 대기
        QueryResponse response = client.waitForResponse(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("STARTED", response.getStatus());
        
        client.disconnect();
    }
    
    @Test
    @Order(2)
    @DisplayName("동시 다중 클라이언트 연결 테스트")
    void testConcurrentClientConnections() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(CONCURRENT_CLIENTS);
        List<CompletableFuture<Boolean>> connectFutures = new ArrayList<>();
        
        // 동시에 여러 클라이언트 연결
        for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
            final int clientId = i;
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    TestClient client = new TestClient("client-" + clientId);
                    boolean connected = client.connect();
                    if (connected) {
                        testClients.add(client);
                        connectLatch.countDown();
                    }
                    return connected;
                } catch (Exception e) {
                    log.error("Client {} connection failed", clientId, e);
                    return false;
                }
            }, executor);
            connectFutures.add(future);
        }
        
        // 모든 연결 완료 대기
        assertTrue(connectLatch.await(30, TimeUnit.SECONDS), "모든 클라이언트 연결 시간 초과");
        
        // 연결 성공 확인
        long connectedCount = connectFutures.stream()
            .map(CompletableFuture::join)
            .filter(connected -> connected)
            .count();
        
        assertEquals(CONCURRENT_CLIENTS, connectedCount, "일부 클라이언트 연결 실패");
    }
    
    @Test
    @Order(3)
    @DisplayName("대용량 데이터 스트리밍 부하 테스트")
    void testHighVolumeStreaming() throws Exception {
        int numClients = 5;
        CountDownLatch queryLatch = new CountDownLatch(numClients);
        Map<String, QueryMetrics> metricsMap = new ConcurrentHashMap<>();
        
        // 클라이언트 생성 및 연결
        for (int i = 0; i < numClients; i++) {
            TestClient client = new TestClient("load-client-" + i);
            assertTrue(client.connect());
            testClients.add(client);
        }
        
        // 각 클라이언트가 대용량 쿼리 실행
        testClients.forEach(client -> {
            executor.submit(() -> {
                try {
                    // 30일 데이터 요청
                    TrackQueryRequest request = TrackQueryRequest.builder()
                        .startTime(LocalDateTime.now().minusDays(30))
                        .endTime(LocalDateTime.now())
                        .viewport(createTestViewport())
                        .chunkSize(1000)
                        .includeStats(true)
                        .build();
                    
                    QueryMetrics metrics = client.executeQueryWithMetrics(request);
                    metricsMap.put(client.getClientId(), metrics);
                    
                } catch (Exception e) {
                    log.error("Query execution failed for {}", client.getClientId(), e);
                } finally {
                    queryLatch.countDown();
                }
            });
        });
        
        // 모든 쿼리 완료 대기 (최대 5분)
        assertTrue(queryLatch.await(5, TimeUnit.MINUTES), "쿼리 실행 시간 초과");
        
        // 메트릭 분석
        analyzeMetrics(metricsMap);
    }
    
    @Test
    @Order(4)
    @DisplayName("쿼리 취소 기능 테스트")
    void testQueryCancellation() throws Exception {
        TestClient client = new TestClient("cancel-test-client");
        assertTrue(client.connect());
        
        // 대용량 쿼리 시작
        TrackQueryRequest request = createLargeQuery();
        client.sendQuery(request);
        
        // 첫 응답 대기
        QueryResponse startResponse = client.waitForResponse(5, TimeUnit.SECONDS);
        assertNotNull(startResponse);
        String queryId = startResponse.getQueryId();
        
        // 몇 개의 청크를 받은 후 취소
        Thread.sleep(2000);
        client.cancelQuery(queryId);
        
        // 취소 응답 확인
        QueryResponse cancelResponse = client.waitForResponse(5, TimeUnit.SECONDS);
        assertNotNull(cancelResponse);
        assertTrue(cancelResponse.getStatus().contains("CANCEL"));
        
        client.disconnect();
    }
    
    // Helper methods
    
    private TrackQueryRequest createTestQuery(int durationDays) {
        return TrackQueryRequest.builder()
            .startTime(LocalDateTime.now().minusDays(durationDays))
            .endTime(LocalDateTime.now())
            .viewport(createTestViewport())
            .chunkSize(500)
            .includeStats(true)
            .simplificationTolerance(0.0001)
            .build();
    }
    
    private TrackQueryRequest createLargeQuery() {
        return TrackQueryRequest.builder()
            .startTime(LocalDateTime.now().minusDays(30))
            .endTime(LocalDateTime.now())
            .viewport(createTestViewport())
            .chunkSize(2000)
            .includeStats(true)
            .build();
    }
    
    private ViewportFilter createTestViewport() {
        ViewportFilter viewport = new ViewportFilter();
        viewport.setMinLon(124.0);
        viewport.setMaxLon(132.0);
        viewport.setMinLat(33.0);
        viewport.setMaxLat(38.0);
        return viewport;
    }
    
    private void analyzeMetrics(Map<String, QueryMetrics> metricsMap) {
        log.info("=== 부하 테스트 결과 분석 ===");
        
        DoubleSummaryStatistics durationStats = metricsMap.values().stream()
            .mapToDouble(QueryMetrics::getTotalDurationSeconds)
            .summaryStatistics();
        
        LongSummaryStatistics chunkStats = metricsMap.values().stream()
            .mapToLong(QueryMetrics::getTotalChunks)
            .summaryStatistics();
        
        LongSummaryStatistics trackStats = metricsMap.values().stream()
            .mapToLong(QueryMetrics::getTotalTracks)
            .summaryStatistics();
        
        log.info("실행 시간: 평균 {:.2f}초, 최소 {:.2f}초, 최대 {:.2f}초",
                durationStats.getAverage(), durationStats.getMin(), durationStats.getMax());
        
        log.info("청크 수: 평균 {}, 총 {}",
                chunkStats.getAverage(), chunkStats.getSum());
        
        log.info("트랙 수: 평균 {}, 총 {}",
                trackStats.getAverage(), trackStats.getSum());
        
        double totalThroughput = trackStats.getSum() / durationStats.getSum();
        log.info("전체 처리량: {:.2f} tracks/second", totalThroughput);
    }
    
    // Inner classes
    
    private class TestClient {
        private final String clientId;
        private StompSession session;
        private final BlockingQueue<QueryResponse> responseQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<TrackChunkResponse> chunkQueue = new LinkedBlockingQueue<>();
        private final AtomicInteger receivedChunks = new AtomicInteger(0);
        private final AtomicLong receivedTracks = new AtomicLong(0);
        
        public TestClient(String clientId) {
            this.clientId = clientId;
        }
        
        public boolean connect() throws Exception {
            StompSessionHandler sessionHandler = new TestSessionHandler();
            session = stompClient.connect(WS_URL, sessionHandler).get(10, TimeUnit.SECONDS);
            
            // 구독 설정
            session.subscribe("/user/queue/tracks/response", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return QueryResponse.class;
                }
                
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    responseQueue.offer((QueryResponse) payload);
                }
            });
            
            session.subscribe("/user/queue/tracks/data", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return TrackChunkResponse.class;
                }
                
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    TrackChunkResponse chunk = (TrackChunkResponse) payload;
                    chunkQueue.offer(chunk);
                    receivedChunks.incrementAndGet();
                    receivedTracks.addAndGet(chunk.getTracks().size());
                }
            });
            
            return session.isConnected();
        }
        
        public void sendQuery(TrackQueryRequest request) {
            session.send("/app/tracks/query", request);
        }
        
        public void cancelQuery(String queryId) {
            session.send("/app/tracks/cancel/" + queryId, null);
        }
        
        public QueryResponse waitForResponse(long timeout, TimeUnit unit) throws InterruptedException {
            return responseQueue.poll(timeout, unit);
        }
        
        public QueryMetrics executeQueryWithMetrics(TrackQueryRequest request) throws Exception {
            long startTime = System.currentTimeMillis();
            receivedChunks.set(0);
            receivedTracks.set(0);
            
            sendQuery(request);
            
            // 첫 응답 대기
            QueryResponse response = waitForResponse(30, TimeUnit.SECONDS);
            if (response == null || !"STARTED".equals(response.getStatus())) {
                throw new RuntimeException("Query start failed");
            }
            
            // 모든 청크 수신 대기
            TrackChunkResponse lastChunk = null;
            while (true) {
                TrackChunkResponse chunk = chunkQueue.poll(30, TimeUnit.SECONDS);
                if (chunk == null) {
                    break;
                }
                
                if (chunk.getIsLastChunk()) {
                    lastChunk = chunk;
                    break;
                }
            }
            
            long endTime = System.currentTimeMillis();
            
            return new QueryMetrics(
                clientId,
                (endTime - startTime) / 1000.0,
                receivedChunks.get(),
                receivedTracks.get()
            );
        }
        
        public void disconnect() {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
        
        public String getClientId() {
            return clientId;
        }
    }
    
    private class TestSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            log.info("Connected to WebSocket server");
        }
        
        @Override
        public void handleException(StompSession session, StompCommand command, 
                                   StompHeaders headers, byte[] payload, Throwable exception) {
            log.error("WebSocket error", exception);
        }
    }
    
    private static class QueryMetrics {
        private final String clientId;
        private final double totalDurationSeconds;
        private final long totalChunks;
        private final long totalTracks;
        
        public QueryMetrics(String clientId, double totalDurationSeconds, 
                          long totalChunks, long totalTracks) {
            this.clientId = clientId;
            this.totalDurationSeconds = totalDurationSeconds;
            this.totalChunks = totalChunks;
            this.totalTracks = totalTracks;
        }
        
        // Getters
        public String getClientId() { return clientId; }
        public double getTotalDurationSeconds() { return totalDurationSeconds; }
        public long getTotalChunks() { return totalChunks; }
        public long getTotalTracks() { return totalTracks; }
    }
}