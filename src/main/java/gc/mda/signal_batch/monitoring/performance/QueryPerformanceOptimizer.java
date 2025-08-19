package gc.mda.signal_batch.monitoring.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 쿼리 성능 최적화기
 * 쿼리 실행 계획 분석, 슬로우 쿼리 감지, 쿼리 튜닝 제안
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryPerformanceOptimizer {

    private final Map<String, QueryStatistics> queryStats = new ConcurrentHashMap<>();
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong slowQueries = new AtomicLong(0);
    
    // 슬로우 쿼리 임계값 (ms)
    private static final long SLOW_QUERY_THRESHOLD = 1000;

    /**
     * 쿼리 실행 시간 측정 및 기록
     */
    public <T> T measureQueryPerformance(String queryId, JdbcTemplate jdbcTemplate, 
                                        QueryExecutor<T> executor) throws Exception {
        long startTime = System.currentTimeMillis();
        Exception queryException = null;
        T result = null;
        
        try {
            result = executor.execute();
            return result;
        } catch (Exception e) {
            queryException = e;
            throw e;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            recordQueryExecution(queryId, executionTime, queryException);
            
            if (executionTime > SLOW_QUERY_THRESHOLD) {
                analyzeSlowQuery(queryId, jdbcTemplate, executionTime);
            }
        }
    }

    /**
     * 쿼리 실행 기록
     */
    private void recordQueryExecution(String queryId, long executionTime, Exception exception) {
        totalQueries.incrementAndGet();
        
        QueryStatistics stats = queryStats.computeIfAbsent(queryId, k -> new QueryStatistics(k));
        stats.recordExecution(executionTime, exception != null);
        
        if (executionTime > SLOW_QUERY_THRESHOLD) {
            slowQueries.incrementAndGet();
        }
    }
    
    /**
     * 쿼리 성능 기록 (public 메서드)
     */
    public void recordQueryPerformance(String queryId, long executionTime) {
        recordQueryExecution(queryId, executionTime, null);
    }

    /**
     * 슬로우 쿼리 분석
     */
    private void analyzeSlowQuery(String queryId, JdbcTemplate jdbcTemplate, long executionTime) {
        log.warn("Slow query detected: {} ({}ms)", queryId, executionTime);
        
        // 실행 계획 분석
        try {
            String explainQuery = "EXPLAIN (ANALYZE, BUFFERS) " + getQueryByIdPattern(queryId);
            List<Map<String, Object>> explainResult = jdbcTemplate.queryForList(explainQuery);
            
            log.info("Query execution plan for {}: {}", queryId, explainResult);
            
            // 최적화 제안 생성
            List<String> suggestions = generateOptimizationSuggestions(explainResult);
            if (!suggestions.isEmpty()) {
                log.info("Optimization suggestions for {}: {}", queryId, suggestions);
            }
        } catch (Exception e) {
            log.error("Failed to analyze slow query {}: {}", queryId, e.getMessage());
        }
    }

    /**
     * 쿼리 ID 패턴으로 실제 쿼리 추출 (간단한 매핑)
     */
    private String getQueryByIdPattern(String queryId) {
        Map<String, String> queryTemplates = Map.of(
            "vessel_latest_position", "SELECT DISTINCT ON (target_id) * FROM signal.sig_test WHERE message_time > NOW() - INTERVAL '5 minutes'",
            "area_statistics", "SELECT * FROM signal.t_area_statistics WHERE time_bucket > NOW() - INTERVAL '1 hour'",
            "grid_tracks", "SELECT * FROM signal.t_grid_vessel_tracks WHERE time_bucket > NOW() - INTERVAL '1 hour'"
        );
        
        return queryTemplates.getOrDefault(queryId, "SELECT 1");
    }

    /**
     * 최적화 제안 생성
     */
    private List<String> generateOptimizationSuggestions(List<Map<String, Object>> explainResult) {
        List<String> suggestions = new ArrayList<>();
        
        for (Map<String, Object> row : explainResult) {
            String plan = String.valueOf(row.get("QUERY PLAN"));
            
            // Sequential Scan 감지
            if (plan.contains("Seq Scan")) {
                suggestions.add("Consider adding an index to avoid sequential scan");
            }
            
            // 높은 비용 감지
            if (plan.contains("cost=") && extractCost(plan) > 10000) {
                suggestions.add("High query cost detected. Consider query restructuring");
            }
            
            // 많은 행 처리 감지
            if (plan.contains("rows=") && extractRows(plan) > 100000) {
                suggestions.add("Processing large number of rows. Consider adding filters or partitioning");
            }
            
            // 임시 디스크 사용 감지
            if (plan.contains("Disk:") || plan.contains("External sort")) {
                suggestions.add("Query using disk for sorting. Consider increasing work_mem");
            }
        }
        
        return suggestions;
    }

    /**
     * 실행 계획에서 비용 추출
     */
    private double extractCost(String plan) {
        try {
            int costIndex = plan.indexOf("cost=");
            if (costIndex != -1) {
                int endIndex = plan.indexOf(" ", costIndex + 5);
                String costStr = plan.substring(costIndex + 5, endIndex);
                return Double.parseDouble(costStr.split("\\.\\.")[1]);
            }
        } catch (Exception e) {
            // 파싱 실패시 0 반환
        }
        return 0;
    }

    /**
     * 실행 계획에서 행 수 추출
     */
    private long extractRows(String plan) {
        try {
            int rowsIndex = plan.indexOf("rows=");
            if (rowsIndex != -1) {
                int endIndex = plan.indexOf(" ", rowsIndex + 5);
                if (endIndex == -1) endIndex = plan.indexOf(")", rowsIndex + 5);
                String rowsStr = plan.substring(rowsIndex + 5, endIndex);
                return Long.parseLong(rowsStr);
            }
        } catch (Exception e) {
            // 파싱 실패시 0 반환
        }
        return 0;
    }

    /**
     * 쿼리 통계 조회
     */
    public Map<String, QueryStatistics> getQueryStatistics() {
        return new HashMap<>(queryStats);
    }

    /**
     * 성능 리포트 생성
     */
    public String generatePerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Query Performance Report ===\n");
        report.append(String.format("Total Queries: %d\n", totalQueries.get()));
        report.append(String.format("Slow Queries: %d (%.2f%%)\n", 
                slowQueries.get(), 
                totalQueries.get() > 0 ? (double) slowQueries.get() / totalQueries.get() * 100 : 0));
        
        report.append("\nTop 10 Slowest Queries:\n");
        queryStats.values().stream()
                .sorted((a, b) -> Long.compare(b.getAverageTime(), a.getAverageTime()))
                .limit(10)
                .forEach(stats -> {
                    report.append(String.format("  %s: avg=%dms, max=%dms, count=%d, errors=%d\n",
                            stats.getQueryId(),
                            stats.getAverageTime(),
                            stats.getMaxTime(),
                            stats.getExecutionCount(),
                            stats.getErrorCount()));
                });
        
        return report.toString();
    }

    /**
     * 쿼리 튜닝 제안
     */
    public List<QueryTuningAdvice> getQueryTuningAdvice() {
        List<QueryTuningAdvice> adviceList = new ArrayList<>();
        
        queryStats.values().forEach(stats -> {
            if (stats.getAverageTime() > SLOW_QUERY_THRESHOLD) {
                QueryTuningAdvice advice = new QueryTuningAdvice();
                advice.setQueryId(stats.getQueryId());
                advice.setAverageTime(stats.getAverageTime());
                advice.setPriority(calculatePriority(stats));
                advice.setSuggestions(generateTuningSuggestions(stats));
                adviceList.add(advice);
            }
        });
        
        adviceList.sort((a, b) -> b.getPriority() - a.getPriority());
        return adviceList;
    }

    /**
     * 튜닝 우선순위 계산
     */
    private int calculatePriority(QueryStatistics stats) {
        // 실행 빈도와 평균 시간을 고려한 우선순위
        long frequency = stats.getExecutionCount();
        long avgTime = stats.getAverageTime();
        
        if (avgTime > 5000 && frequency > 100) return 5; // 최고 우선순위
        if (avgTime > 3000 && frequency > 50) return 4;
        if (avgTime > 2000 && frequency > 20) return 3;
        if (avgTime > 1000) return 2;
        return 1;
    }

    /**
     * 튜닝 제안 생성
     */
    private List<String> generateTuningSuggestions(QueryStatistics stats) {
        List<String> suggestions = new ArrayList<>();
        
        if (stats.getAverageTime() > 5000) {
            suggestions.add("Critical performance issue. Consider complete query redesign");
        }
        
        if (stats.getMaxTime() > stats.getAverageTime() * 3) {
            suggestions.add("High variance in execution time. Check for lock contention");
        }
        
        if (stats.getErrorCount() > 0) {
            suggestions.add("Query has errors. Fix errors before performance tuning");
        }
        
        // 쿼리별 특화 제안
        switch (stats.getQueryId()) {
            case "vessel_latest_position":
                suggestions.add("Consider partitioning sig_test table by time");
                suggestions.add("Add index on (target_id, message_time DESC)");
                break;
            case "area_statistics":
                suggestions.add("Consider materialized view for area statistics");
                break;
            case "grid_tracks":
                suggestions.add("Use spatial index for grid-based queries");
                break;
        }
        
        return suggestions;
    }

    // 함수형 인터페이스
    @FunctionalInterface
    public interface QueryExecutor<T> {
        T execute() throws Exception;
    }

    // 내부 클래스들
    public static class QueryStatistics {
        private final String queryId;
        private long executionCount = 0;
        private long totalTime = 0;
        private long maxTime = 0;
        private long minTime = Long.MAX_VALUE;
        private long errorCount = 0;

        public QueryStatistics(String queryId) {
            this.queryId = queryId;
        }

        public synchronized void recordExecution(long executionTime, boolean hasError) {
            executionCount++;
            totalTime += executionTime;
            maxTime = Math.max(maxTime, executionTime);
            minTime = Math.min(minTime, executionTime);
            if (hasError) errorCount++;
        }

        public String getQueryId() { return queryId; }
        public long getExecutionCount() { return executionCount; }
        public long getAverageTime() { return executionCount > 0 ? totalTime / executionCount : 0; }
        public long getMaxTime() { return maxTime; }
        public long getMinTime() { return minTime == Long.MAX_VALUE ? 0 : minTime; }
        public long getErrorCount() { return errorCount; }
    }

    public static class QueryTuningAdvice {
        private String queryId;
        private long averageTime;
        private int priority;
        private List<String> suggestions;

        // Getter/Setter
        public String getQueryId() { return queryId; }
        public void setQueryId(String queryId) { this.queryId = queryId; }
        public long getAverageTime() { return averageTime; }
        public void setAverageTime(long averageTime) { this.averageTime = averageTime; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    }
}