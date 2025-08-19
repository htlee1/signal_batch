package gc.mda.signal_batch.monitoring.controller;

import gc.mda.signal_batch.monitoring.performance.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;


/**
 * 성능 최적화 관리 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/performance")
@RequiredArgsConstructor
@Tag(name = "Performance", description = "성능 최적화 및 모니터링 API")
public class PerformanceOptimizationController {

    private final PerformanceOptimizationManager optimizationManager;
    private final QueryPerformanceOptimizer queryOptimizer;
    private final BatchProcessingOptimizer batchOptimizer;
    private final DatabaseIndexOptimizer indexOptimizer;
    private final JdbcTemplate queryJdbcTemplate;

    @GetMapping("/status")
    @Operation(summary = "성능 최적화 상태 조회", description = "캐시, 메모리, 스레드풀 등의 상태를 조회합니다")
    public ResponseEntity<PerformanceOptimizationManager.PerformanceStatus> getPerformanceStatus() {
        return ResponseEntity.ok(optimizationManager.getStatus());
    }

    @GetMapping("/report")
    @Operation(summary = "통합 성능 리포트 조회", description = "모든 성능 관련 통계를 통합한 리포트를 생성합니다")
    public ResponseEntity<Map<String, Object>> getPerformanceReport() {
        Map<String, Object> report = new HashMap<>();
        
        // 전체 성능 리포트
        report.put("optimization", optimizationManager.generatePerformanceReport());
        report.put("query", queryOptimizer.generatePerformanceReport());
        report.put("batch", batchOptimizer.generateOptimizationReport());
        
        // 현재 상태
        report.put("currentStatus", optimizationManager.getStatus());
        report.put("queryStats", queryOptimizer.getQueryStatistics());
        
        return ResponseEntity.ok(report);
    }

    @GetMapping("/query/statistics")
    @Operation(summary = "쿼리 통계 조회", description = "각 쿼리의 실행 통계를 조회합니다")
    public ResponseEntity<Map<String, QueryPerformanceOptimizer.QueryStatistics>> getQueryStatistics() {
        return ResponseEntity.ok(queryOptimizer.getQueryStatistics());
    }

    @GetMapping("/query/tuning-advice")
    @Operation(summary = "쿼리 튜닝 조언 조회", description = "성능이 낮은 쿼리에 대한 튜닝 조언을 제공합니다")
    public ResponseEntity<List<QueryPerformanceOptimizer.QueryTuningAdvice>> getQueryTuningAdvice() {
        return ResponseEntity.ok(queryOptimizer.getQueryTuningAdvice());
    }

    @PostMapping("/memory/optimize")
    @Operation(summary = "메모리 최적화 실행", description = "메모리 최적화를 수동으로 실행합니다")
    public ResponseEntity<Map<String, Object>> performMemoryOptimization() {
        PerformanceOptimizationManager.MemoryUsage beforeMemory = optimizationManager.getStatus().getMemoryUsage();
        
        optimizationManager.performMemoryOptimization();
        
        PerformanceOptimizationManager.MemoryUsage afterMemory = optimizationManager.getStatus().getMemoryUsage();
        
        Map<String, Object> result = new HashMap<>();
        result.put("before", beforeMemory);
        result.put("after", afterMemory);
        result.put("freedMemoryMB", (beforeMemory.getUsed() - afterMemory.getUsed()) / 1024 / 1024);
        result.put("message", "Memory optimization completed");
        
        log.info("Memory optimization performed manually");
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/batch/recommendations")
    @Operation(summary = "배치 처리 최적화 권장사항", description = "현재 시스템 상태에 따른 배치 처리 최적화 권장사항을 제공합니다")
    public ResponseEntity<BatchProcessingOptimizer.OptimizationRecommendation> getBatchRecommendations() {
        return ResponseEntity.ok(batchOptimizer.analyzeMemoryUsage());
    }

    @PostMapping("/batch/reset")
    @Operation(summary = "배치 최적화 설정 리셋", description = "배치 처리 최적화 설정을 초기값으로 리셋합니다")
    public ResponseEntity<Map<String, String>> resetBatchOptimizations() {
        batchOptimizer.resetOptimizations();
        
        Map<String, String> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Batch processing optimizations have been reset");
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/thread-pools")
    @Operation(summary = "스레드풀 상태 조회", description = "활성 스레드풀의 상태를 조회합니다")
    public ResponseEntity<List<PerformanceOptimizationManager.ThreadPoolStatus>> getThreadPoolStatus() {
        return ResponseEntity.ok(optimizationManager.getStatus().getThreadPoolStatuses());
    }

    @PostMapping("/thread-pool/create")
    @Operation(summary = "최적화된 스레드풀 생성", description = "새로운 최적화된 스레드풀을 생성합니다")
    public ResponseEntity<Map<String, Object>> createThreadPool(
            @RequestParam String name,
            @RequestParam(defaultValue = "4") int coreSize,
            @RequestParam(defaultValue = "8") int maxSize,
            @RequestParam(defaultValue = "100") int queueCapacity) {
        
        optimizationManager.createOptimizedThreadPool(name, coreSize, maxSize, queueCapacity);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("name", name);
        result.put("coreSize", coreSize);
        result.put("maxSize", maxSize);
        result.put("queueCapacity", queueCapacity);
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/cache/hit-rate")
    @Operation(summary = "캐시 히트율 조회", description = "현재 캐시의 히트율을 조회합니다")
    public ResponseEntity<Map<String, Object>> getCacheHitRate() {
        PerformanceOptimizationManager.PerformanceStatus status = optimizationManager.getStatus();
        
        Map<String, Object> result = new HashMap<>();
        result.put("hitRate", status.getCacheHitRate());
        result.put("cacheSize", status.getCacheSize());
        result.put("message", String.format("Cache hit rate: %.2f%%", status.getCacheHitRate()));
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/diagnostics")
    @Operation(summary = "성능 진단 실행", description = "시스템 전반의 성능 진단을 실행합니다")
    public ResponseEntity<Map<String, Object>> runPerformanceDiagnostics() {
        Map<String, Object> diagnostics = new HashMap<>();
        
        // 성능 상태
        PerformanceOptimizationManager.PerformanceStatus status = optimizationManager.getStatus();
        diagnostics.put("currentStatus", status);
        
        // 문제점 진단
        List<String> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        // 메모리 체크
        if (status.getMemoryUsage().getPercentage() > 80) {
            issues.add("High memory usage detected");
            recommendations.add("Consider increasing heap size or optimizing memory usage");
        }
        
        // 캐시 히트율 체크
        if (status.getCacheHitRate() < 70) {
            issues.add("Low cache hit rate");
            recommendations.add("Review cache configuration and query patterns");
        }
        
        // 스레드풀 체크
        for (PerformanceOptimizationManager.ThreadPoolStatus poolStatus : status.getThreadPoolStatuses()) {
            if (poolStatus.getQueueSize() > 50) {
                issues.add(String.format("High queue size in thread pool: %s", poolStatus.getName()));
                recommendations.add(String.format("Consider increasing thread pool size for %s", poolStatus.getName()));
            }
        }
        
        // 쿼리 성능 체크
        List<QueryPerformanceOptimizer.QueryTuningAdvice> tuningAdvice = queryOptimizer.getQueryTuningAdvice();
        if (!tuningAdvice.isEmpty()) {
            issues.add(String.format("%d slow queries detected", tuningAdvice.size()));
            recommendations.add("Review and optimize slow queries based on tuning advice");
        }
        
        diagnostics.put("issues", issues);
        diagnostics.put("recommendations", recommendations);
        diagnostics.put("severity", issues.size() > 3 ? "HIGH" : issues.size() > 1 ? "MEDIUM" : "LOW");
        
        return ResponseEntity.ok(diagnostics);
    }

    @GetMapping("/index/analysis")
    @Operation(summary = "데이터베이스 인덱스 분석", description = "현재 인덱스 상태를 분석하고 최적화 권장사항을 제공합니다")
    public ResponseEntity<DatabaseIndexOptimizer.IndexAnalysisReport> analyzeIndexes() {
        return ResponseEntity.ok(indexOptimizer.analyzeIndexes());
    }

    @GetMapping("/index/missing")
    @Operation(summary = "누락된 인덱스 조회", description = "성능 향상을 위해 필요한 누락된 인덱스를 조회합니다")
    public ResponseEntity<List<DatabaseIndexOptimizer.MissingIndexSuggestion>> getMissingIndexes() {
        DatabaseIndexOptimizer.IndexAnalysisReport report = indexOptimizer.analyzeIndexes();
        return ResponseEntity.ok(report.getMissingIndexes());
    }

    @GetMapping("/index/unused")
    @Operation(summary = "사용하지 않는 인덱스 조회", description = "삭제 가능한 사용하지 않는 인덱스를 조회합니다")
    public ResponseEntity<List<DatabaseIndexOptimizer.UnusedIndex>> getUnusedIndexes() {
        DatabaseIndexOptimizer.IndexAnalysisReport report = indexOptimizer.analyzeIndexes();
        return ResponseEntity.ok(report.getUnusedIndexes());
    }

    @GetMapping("/index/creation-sql")
    @Operation(summary = "인덱스 생성 SQL 조회", description = "누락된 인덱스를 생성하기 위한 SQL을 생성합니다")
    public ResponseEntity<Map<String, Object>> getIndexCreationSQL() {
        DatabaseIndexOptimizer.IndexAnalysisReport report = indexOptimizer.analyzeIndexes();
        List<String> sqlStatements = indexOptimizer.generateIndexCreationSQL(report.getMissingIndexes());
        
        Map<String, Object> result = new HashMap<>();
        result.put("missingIndexes", report.getMissingIndexes());
        result.put("sqlStatements", sqlStatements);
        result.put("message", "Execute these SQL statements to create missing indexes");
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/index/current-status")
    @Operation(summary = "현재 인덱스 상태 확인", description = "QueryDB의 실제 인덱스 상태를 확인합니다")
    public ResponseEntity<Map<String, Object>> getCurrentIndexStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // 주요 테이블 목록
        List<String> targetTables = Arrays.asList(
            "t_vessel_tracks_5min",
            "t_grid_vessel_tracks", 
            "t_area_vessel_tracks",
            "t_vessel_tracks_hourly",
            "t_grid_tracks_summary_hourly",
            "t_area_tracks_summary_hourly",
            "t_vessel_tracks_daily",
            "t_grid_tracks_summary_daily",
            "t_area_tracks_summary_daily",
            "t_vessel_latest_position",
            "t_tile_summary",
            "t_area_statistics"
        );
        
        // 각 테이블의 인덱스 정보 수집
        Map<String, List<Map<String, Object>>> tableIndexInfo = new HashMap<>();
        
        for (String tableName : targetTables) {
            String sql = """
                SELECT 
                    i.indexname,
                    i.indexdef,
                    pg_size_pretty(pg_relation_size(i.indexname::regclass)) as index_size,
                    COALESCE(s.idx_scan, 0) as scan_count
                FROM pg_indexes i
                LEFT JOIN pg_stat_user_indexes s 
                    ON i.schemaname = s.schemaname 
                    AND i.tablename = s.tablename 
                    AND i.indexname = s.indexname
                WHERE i.schemaname = 'signal' 
                    AND i.tablename = ?
                ORDER BY i.indexname
            """;
            
            try {
                List<Map<String, Object>> indexes = queryJdbcTemplate.queryForList(sql, tableName);
                tableIndexInfo.put(tableName, indexes);
            } catch (Exception e) {
                log.error("Failed to get indexes for table {}: {}", tableName, e.getMessage());
                tableIndexInfo.put(tableName, new ArrayList<>());
            }
        }
        
        status.put("tableIndexInfo", tableIndexInfo);
        
        // 인덱스 통계
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalTables", targetTables.size());
        statistics.put("totalIndexes", tableIndexInfo.values().stream()
            .mapToInt(List::size).sum());
        
        // 테이블별 인덱스 개수
        Map<String, Integer> indexCountByTable = new HashMap<>();
        tableIndexInfo.forEach((table, indexes) -> 
            indexCountByTable.put(table, indexes.size())
        );
        statistics.put("indexCountByTable", indexCountByTable);
        
        status.put("statistics", statistics);
        
        return ResponseEntity.ok(status);
    }

    @GetMapping("/index/missing-analysis")
    @Operation(summary = "누락된 인덱스 분석", description = "권장 인덱스와 비교하여 누락된 인덱스를 분석합니다")
    public ResponseEntity<Map<String, Object>> analyzeMissingIndexes() {
        Map<String, Object> analysis = new HashMap<>();
        
        // 현재 인덱스 상태 확인
        DatabaseIndexOptimizer.IndexAnalysisReport report = indexOptimizer.analyzeIndexes();
        
        // 누락된 인덱스 상세 분석
        Map<String, List<Map<String, Object>>> missingByTable = new HashMap<>();
        
        for (DatabaseIndexOptimizer.MissingIndexSuggestion missing : report.getMissingIndexes()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("columns", missing.getColumns());
            detail.put("reason", missing.getReason());
            detail.put("priority", missing.getPriority());
            detail.put("createSQL", generateCreateSQL(missing));
            
            missingByTable.computeIfAbsent(missing.getTableName(), k -> new ArrayList<>())
                .add(detail);
        }
        
        analysis.put("missingIndexesByTable", missingByTable);
        analysis.put("totalMissingIndexes", report.getMissingIndexes().size());
        
        // 우선순위별 분류
        Map<Integer, List<DatabaseIndexOptimizer.MissingIndexSuggestion>> byPriority = 
            report.getMissingIndexes().stream()
                .collect(Collectors.groupingBy(DatabaseIndexOptimizer.MissingIndexSuggestion::getPriority));
        
        Map<String, Object> priorityAnalysis = new HashMap<>();
        byPriority.forEach((priority, indexes) -> {
            priorityAnalysis.put("priority_" + priority, indexes.size());
        });
        analysis.put("byPriority", priorityAnalysis);
        
        // 생성 SQL 스크립트
        List<String> allCreateSQLs = indexOptimizer.generateIndexCreationSQL(report.getMissingIndexes());
        analysis.put("createSQLScript", String.join("\n\n", allCreateSQLs));
        
        return ResponseEntity.ok(analysis);
    }
    
    private String generateCreateSQL(DatabaseIndexOptimizer.MissingIndexSuggestion suggestion) {
        String indexName = "idx_" + suggestion.getTableName() + "_" + 
            suggestion.getColumns().replaceAll("[\\s(),]", "_")
                                   .replaceAll("_+", "_")
                                   .toLowerCase();
        
        if (suggestion.getColumns().contains("GIST")) {
            return String.format(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON signal.%s %s;",
                indexName, suggestion.getTableName(), suggestion.getColumns()
            );
        } else {
            return String.format(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON signal.%s (%s);",
                indexName, suggestion.getTableName(), suggestion.getColumns()
            );
        }
    }
}