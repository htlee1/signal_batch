package gc.mda.signal_batch.monitoring.performance;

import gc.mda.signal_batch.global.config.PerformanceOptimizationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 배치 처리 최적화기
 * 동적 청크 크기 조정, 병렬 처리 최적화, 리소스 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchProcessingOptimizer {

    private final PerformanceOptimizationProperties optimizationProperties;

    @Value("${vessel.batch.chunk-size:5000}")
    private int defaultChunkSize;

    @Value("${vessel.batch.partition-size:12}")
    private int defaultPartitionSize;

    @Value("${vessel.batch.bulk-insert.batch-size:5000}")
    private int defaultBulkBatchSize;

    // 성능 메트릭
    private final Map<String, StepMetrics> stepMetricsMap = new ConcurrentHashMap<>();
    private final AtomicInteger totalOptimizations = new AtomicInteger(0);

    /**
     * 동적 청크 크기 계산
     */
    public int calculateOptimalChunkSize(String stepName, int currentSize, long processingTime, int recordCount) {
        StepMetrics metrics = stepMetricsMap.computeIfAbsent(stepName, k -> new StepMetrics());
        metrics.recordExecution(processingTime, recordCount);

        // 처리 시간이 너무 길면 청크 크기 감소
        if (processingTime > 30000) { // 30초 이상
            int newSize = (int) (currentSize * 0.8);
            log.info("Reducing chunk size for {} from {} to {} due to long processing time", 
                    stepName, currentSize, newSize);
            totalOptimizations.incrementAndGet();
            return Math.max(newSize, optimizationProperties.getChunk().getMinSize());
        }

        // 처리 시간이 너무 짧으면 청크 크기 증가
        if (processingTime < 5000 && recordCount == currentSize) { // 5초 미만이고 전체 처리
            int newSize = (int) (currentSize * 1.2);
            log.info("Increasing chunk size for {} from {} to {} for better throughput", 
                    stepName, currentSize, newSize);
            totalOptimizations.incrementAndGet();
            return Math.min(newSize, optimizationProperties.getChunk().getMaxSize());
        }

        return currentSize;
    }

    /**
     * 동적 파티션 크기 계산
     */
    public int calculateOptimalPartitionSize(int totalRecords, long availableMemory) {
        // 메모리 기반 파티션 크기 계산
        long memoryPerPartition = 100 * 1024 * 1024; // 100MB per partition
        int maxPartitionsByMemory = (int) (availableMemory / memoryPerPartition);

        // 레코드 수 기반 파티션 크기 계산
        int recordsPerPartition = 100000; // 파티션당 10만건
        int maxPartitionsByRecords = Math.max(1, totalRecords / recordsPerPartition);

        // 두 값 중 작은 값 선택
        int optimalSize = Math.min(maxPartitionsByMemory, maxPartitionsByRecords);
        
        // 기본값과 비교하여 조정
        if (optimalSize < defaultPartitionSize) {
            log.warn("Reducing partition size from {} to {} due to resource constraints", 
                    defaultPartitionSize, optimalSize);
            totalOptimizations.incrementAndGet();
        }

        return Math.max(optimalSize, 1); // 최소 1개
    }

    /**
     * 벌크 삽입 배치 크기 최적화
     */
    public int optimizeBulkBatchSize(String tableName, long insertTime, int currentBatchSize) {
        // 삽입 시간 기반 동적 조정
        double timePerRecord = (double) insertTime / currentBatchSize;
        
        if (timePerRecord > 1.0) { // 레코드당 1ms 이상
            int newSize = (int) (currentBatchSize * 0.7);
            log.info("Reducing bulk batch size for {} from {} to {} due to slow inserts", 
                    tableName, currentBatchSize, newSize);
            totalOptimizations.incrementAndGet();
            return Math.max(newSize, 1000);
        }
        
        if (timePerRecord < 0.1) { // 레코드당 0.1ms 미만
            int newSize = (int) (currentBatchSize * 1.5);
            log.info("Increasing bulk batch size for {} from {} to {} for better throughput", 
                    tableName, currentBatchSize, newSize);
            totalOptimizations.incrementAndGet();
            return Math.min(newSize, 10000);
        }
        
        return currentBatchSize;
    }

    /**
     * Step 실행 컨텍스트 최적화
     */
    public void optimizeStepContext(StepExecution stepExecution) {
        ExecutionContext context = stepExecution.getExecutionContext();
        String stepName = stepExecution.getStepName();
        
        // 현재 청크 크기 조회
        int currentChunkSize = context.getInt("chunkSize", defaultChunkSize);
        
        // 메트릭 기반 최적화
        StepMetrics metrics = stepMetricsMap.get(stepName);
        if (metrics != null && metrics.getExecutionCount() > 5) {
            // 평균 처리 시간 기반 청크 크기 조정
            long avgProcessingTime = metrics.getAverageProcessingTime();
            int avgRecordCount = (int) metrics.getAverageRecordCount();
            
            int optimalChunkSize = calculateOptimalChunkSize(
                    stepName, currentChunkSize, avgProcessingTime, avgRecordCount
            );
            
            if (optimalChunkSize != currentChunkSize) {
                context.putInt("chunkSize", optimalChunkSize);
                log.info("Updated chunk size for {} from {} to {}", 
                        stepName, currentChunkSize, optimalChunkSize);
            }
        }
    }

    /**
     * 메모리 사용량 기반 최적화
     */
    public OptimizationRecommendation analyzeMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        double usagePercentage = (double) usedMemory / maxMemory * 100;
        
        OptimizationRecommendation recommendation = new OptimizationRecommendation();
        recommendation.setMemoryUsagePercentage(usagePercentage);
        
        if (usagePercentage > 80) {
            recommendation.setSeverity("HIGH");
            recommendation.addRecommendation("Reduce chunk sizes to prevent OutOfMemoryError");
            recommendation.addRecommendation("Consider increasing heap size");
            recommendation.addRecommendation("Enable more aggressive garbage collection");
        } else if (usagePercentage > 60) {
            recommendation.setSeverity("MEDIUM");
            recommendation.addRecommendation("Monitor memory usage closely");
            recommendation.addRecommendation("Consider optimizing data structures");
        } else if (usagePercentage < 30) {
            recommendation.setSeverity("LOW");
            recommendation.addRecommendation("Memory usage is low, consider increasing chunk sizes");
            recommendation.addRecommendation("Can handle more parallel processing");
        }
        
        return recommendation;
    }

    /**
     * 성능 리포트 생성
     */
    public String generateOptimizationReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Batch Processing Optimization Report ===\n");
        report.append(String.format("Total Optimizations: %d\n", totalOptimizations.get()));
        
        report.append("\nStep Metrics:\n");
        stepMetricsMap.forEach((stepName, metrics) -> {
            report.append(String.format("  %s:\n", stepName));
            report.append(String.format("    Executions: %d\n", metrics.getExecutionCount()));
            report.append(String.format("    Avg Processing Time: %d ms\n", metrics.getAverageProcessingTime()));
            report.append(String.format("    Avg Record Count: %.0f\n", metrics.getAverageRecordCount()));
            report.append(String.format("    Throughput: %.2f records/sec\n", metrics.getThroughput()));
        });
        
        // 메모리 분석
        OptimizationRecommendation memoryRec = analyzeMemoryUsage();
        report.append("\nMemory Analysis:\n");
        report.append(String.format("  Usage: %.2f%%\n", memoryRec.getMemoryUsagePercentage()));
        report.append(String.format("  Severity: %s\n", memoryRec.getSeverity()));
        memoryRec.getRecommendations().forEach(rec -> 
            report.append(String.format("  - %s\n", rec))
        );
        
        return report.toString();
    }

    /**
     * 최적화 설정 리셋
     */
    public void resetOptimizations() {
        stepMetricsMap.clear();
        totalOptimizations.set(0);
        log.info("Batch processing optimizations reset");
    }

    // 내부 클래스들
    private static class StepMetrics {
        private long totalProcessingTime = 0;
        private long totalRecordCount = 0;
        private int executionCount = 0;

        public synchronized void recordExecution(long processingTime, int recordCount) {
            totalProcessingTime += processingTime;
            totalRecordCount += recordCount;
            executionCount++;
        }

        public int getExecutionCount() { return executionCount; }
        public long getAverageProcessingTime() { 
            return executionCount > 0 ? totalProcessingTime / executionCount : 0; 
        }
        public double getAverageRecordCount() { 
            return executionCount > 0 ? (double) totalRecordCount / executionCount : 0; 
        }
        public double getThroughput() {
            long avgTime = getAverageProcessingTime();
            return avgTime > 0 ? getAverageRecordCount() / (avgTime / 1000.0) : 0;
        }
    }

    public static class OptimizationRecommendation {
        private double memoryUsagePercentage;
        private String severity;
        private final List<String> recommendations = new ArrayList<>();

        public double getMemoryUsagePercentage() { return memoryUsagePercentage; }
        public void setMemoryUsagePercentage(double percentage) { this.memoryUsagePercentage = percentage; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public List<String> getRecommendations() { return recommendations; }
        public void addRecommendation(String recommendation) { recommendations.add(recommendation); }
    }
}