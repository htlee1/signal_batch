package gc.mda.signal_batch.domain.vessel.service.query;

import gc.mda.signal_batch.global.websocket.dto.QueryStatusUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


@Slf4j
@Component
public class CancellableQueryManager {
    
    // 활성 쿼리 컨텍스트 관리
    private final Map<String, QueryExecutionContext> activeQueries = new ConcurrentHashMap<>();
    
    // 쿼리 실행을 위한 스레드 풀
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("query-executor-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    
    /**
     * 취소 가능한 쿼리 실행
     */
    public CompletableFuture<Void> executeQuery(
            String queryId,
            QueryTask queryTask,
            Consumer<QueryStatusUpdate> statusConsumer) {
        
        QueryExecutionContext context = new QueryExecutionContext(queryId, statusConsumer);
        activeQueries.put(queryId, context);
        
        return CompletableFuture.runAsync(() -> {
            try {
                // 쿼리 시작 알림
                context.updateStatus("STARTED", "Query execution started", 0.0);
                
                // 쿼리 실행
                queryTask.execute(context);
                
                // 완료 처리
                if (!context.isCancelled()) {
                    context.updateStatus("COMPLETED", "Query completed successfully", 100.0);
                }
            } catch (Exception e) {
                if (context.isCancelled()) {
                    context.updateStatus("CANCELLED", "Query cancelled by user", context.getProgress());
                } else {
                    log.error("Query {} failed: {}", queryId, e.getMessage(), e);
                    context.updateStatus("ERROR", "Query failed: " + e.getMessage(), context.getProgress());
                }
            } finally {
                activeQueries.remove(queryId);
                context.cleanup();
            }
        }, queryExecutor);
    }
    
    /**
     * 쿼리 취소
     */
    public boolean cancelQuery(String queryId) {
        QueryExecutionContext context = activeQueries.get(queryId);
        if (context != null) {
            context.cancel();
            return true;
        }
        return false;
    }
    
    /**
     * 모든 활성 쿼리 취소
     */
    public void cancelAllQueries() {
        activeQueries.values().forEach(QueryExecutionContext::cancel);
    }
    
    /**
     * 쿼리 실행 컨텍스트
     */
    public static class QueryExecutionContext {
        private final String queryId;
        private final Consumer<QueryStatusUpdate> statusConsumer;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Map<String, Statement> activeStatements = new ConcurrentHashMap<>();
        private volatile double progress = 0.0;
        
        public QueryExecutionContext(String queryId, Consumer<QueryStatusUpdate> statusConsumer) {
            this.queryId = queryId;
            this.statusConsumer = statusConsumer;
        }
        
        /**
         * 취소 여부 확인
         */
        public boolean isCancelled() {
            return cancelled.get();
        }
        
        /**
         * 쿼리 취소
         */
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                log.info("Cancelling query: {}", queryId);
                
                // 모든 활성 Statement 취소
                activeStatements.values().forEach(stmt -> {
                    try {
                        if (!stmt.isClosed()) {
                            stmt.cancel();
                        }
                    } catch (SQLException e) {
                        log.warn("Failed to cancel statement for query {}: {}", queryId, e.getMessage());
                    }
                });
                
                updateStatus("CANCELLING", "Query cancellation requested", progress);
            }
        }
        
        /**
         * Statement 등록 (취소를 위해)
         */
        public void registerStatement(String key, Statement statement) {
            activeStatements.put(key, statement);
        }
        
        /**
         * Statement 등록 해제
         */
        public void unregisterStatement(String key) {
            Statement stmt = activeStatements.remove(key);
            if (stmt != null) {
                try {
                    if (!stmt.isClosed()) {
                        stmt.close();
                    }
                } catch (SQLException e) {
                    log.warn("Failed to close statement: {}", e.getMessage());
                }
            }
        }
        
        /**
         * 진행률 업데이트
         */
        public void updateProgress(double progress) {
            this.progress = Math.min(100.0, Math.max(0.0, progress));
        }
        
        /**
         * 진행률 조회
         */
        public double getProgress() {
            return progress;
        }
        
        /**
         * 상태 업데이트
         */
        public void updateStatus(String status, String message, double progress) {
            updateProgress(progress);
            QueryStatusUpdate update = new QueryStatusUpdate();
            update.setQueryId(queryId);
            update.setStatus(status);
            update.setMessage(message);
            update.setProgressPercentage(this.progress);
            statusConsumer.accept(update);
        }
        
        /**
         * 리소스 정리
         */
        public void cleanup() {
            activeStatements.keySet().forEach(this::unregisterStatement);
        }
        
        /**
         * 취소 확인 포인트
         * 주기적으로 호출하여 취소 여부 확인
         */
        public void checkCancellation() throws CancellationException {
            if (isCancelled()) {
                throw new CancellationException("Query cancelled by user");
            }
        }
    }
    
    /**
     * 쿼리 실행 태스크 인터페이스
     */
    @FunctionalInterface
    public interface QueryTask {
        void execute(QueryExecutionContext context) throws Exception;
    }
    
    /**
     * 청크 단위 쿼리 실행을 위한 헬퍼 메서드
     */
    public void executeChunkedQuery(
            QueryExecutionContext context,
            Connection connection,
            String sql,
            ChunkProcessor processor,
            int totalExpected) throws SQLException {
        
        try (Statement stmt = connection.createStatement()) {
            // 취소를 위해 Statement 등록
            context.registerStatement("main-query", stmt);
            
            // 스트리밍 모드 설정
            stmt.setFetchSize(1000);
            
            try (var rs = stmt.executeQuery(sql)) {
                int processed = 0;
                
                while (rs.next()) {
                    // 주기적으로 취소 확인
                    if (processed % 100 == 0) {
                        context.checkCancellation();
                        
                        // 진행률 업데이트
                        double progress = totalExpected > 0 ? 
                            (processed * 100.0 / totalExpected) : 0.0;
                        context.updateProgress(progress);
                    }
                    
                    // 청크 처리
                    processor.processRow(rs);
                    processed++;
                }
            } finally {
                context.unregisterStatement("main-query");
            }
        }
    }
    
    /**
     * 청크 프로세서 인터페이스
     */
    @FunctionalInterface
    public interface ChunkProcessor {
        void processRow(java.sql.ResultSet rs) throws SQLException;
    }
}