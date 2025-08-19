package gc.mda.signal_batch.monitoring.performance;

import gc.mda.signal_batch.domain.gis.cache.AreaBoundaryCache;
import gc.mda.signal_batch.global.util.VesselDataHolder;
import gc.mda.signal_batch.global.util.VesselTrackDataHolder;
import gc.mda.signal_batch.monitoring.health.BatchMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 성능 최적화 통합 관리자
 * 모든 성능 최적화 컴포넌트를 통합 관리하고 모니터링
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceOptimizationManager {

    private final AreaBoundaryCache areaCache;
    private final VesselDataHolder vesselDataHolder;
    private final VesselTrackDataHolder vesselTrackDataHolder;
    private final BatchMetricsCollector metricsCollector;

    // 성능 카운터
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong memoryOptimizations = new AtomicLong(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // 스레드풀 관리
    private final ConcurrentHashMap<String, ThreadPoolExecutor> threadPools = new ConcurrentHashMap<>();

    /**
     * 성능 최적화 상태 확인
     */
    public PerformanceStatus getStatus() {
        PerformanceStatus status = new PerformanceStatus();
        
        // 캐시 상태
        status.setCacheHitRate(calculateCacheHitRate());
        status.setCacheSize(areaCache.getCacheSize());
        
        // 메모리 상태
        status.setMemoryUsage(getMemoryUsage());
        status.setDataHolderSize(vesselDataHolder.size() + vesselTrackDataHolder.size());
        
        // 스레드풀 상태
        threadPools.forEach((name, pool) -> {
            ThreadPoolStatus poolStatus = new ThreadPoolStatus();
            poolStatus.setName(name);
            poolStatus.setActiveCount(pool.getActiveCount());
            poolStatus.setPoolSize(pool.getPoolSize());
            poolStatus.setQueueSize(pool.getQueue().size());
            poolStatus.setCompletedTaskCount(pool.getCompletedTaskCount());
            status.getThreadPoolStatuses().add(poolStatus);
        });
        
        // 연결 상태
        status.setActiveConnections(activeConnections.get());
        
        return status;
    }

    /**
     * 캐시 히트율 계산
     */
    private double calculateCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total * 100 : 0;
    }

    /**
     * 메모리 사용량 조회
     */
    private MemoryUsage getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage usage = new MemoryUsage();
        usage.setTotal(runtime.totalMemory());
        usage.setUsed(runtime.totalMemory() - runtime.freeMemory());
        usage.setMax(runtime.maxMemory());
        usage.setPercentage((double) usage.getUsed() / usage.getMax() * 100);
        return usage;
    }

    /**
     * 캐시 히트 기록
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
        metricsCollector.recordCacheHit();
    }

    /**
     * 캐시 미스 기록
     */
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
        metricsCollector.recordCacheMiss();
    }

    /**
     * 스레드풀 생성 및 등록
     */
    public ThreadPoolExecutor createOptimizedThreadPool(String name, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            coreSize,
            maxSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName(name + "-" + counter.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        executor.prestartCoreThread();
        threadPools.put(name, executor);
        
        log.info("Created optimized thread pool: {} (core: {}, max: {}, queue: {})",
                name, coreSize, maxSize, queueCapacity);
        
        return executor;
    }

    /**
     * 메모리 최적화 수행
     */
    public void performMemoryOptimization() {
        long beforeMemory = getMemoryUsage().getUsed();
        
        // 데이터 홀더 정리
        vesselDataHolder.clear();
        vesselTrackDataHolder.clear();
        
        // 캐시 정리 (필요시)
        if (getMemoryUsage().getPercentage() > 80) {
            areaCache.clearCache();
            log.warn("Memory usage high, cleared area cache");
        }
        
        // GC 제안
        System.gc();
        
        long afterMemory = getMemoryUsage().getUsed();
        long freedMemory = beforeMemory - afterMemory;
        
        memoryOptimizations.incrementAndGet();
        metricsCollector.recordMemoryOptimization(freedMemory);
        
        log.info("Memory optimization performed. Freed: {} MB", freedMemory / 1024 / 1024);
    }

    /**
     * 연결 모니터링
     */
    public void trackConnection(DataSource dataSource, String name) {
        try (Connection conn = dataSource.getConnection()) {
            activeConnections.incrementAndGet();
            log.debug("Active {} connections: {}", name, activeConnections.get());
        } catch (SQLException e) {
            log.error("Failed to track connection for {}: {}", name, e.getMessage());
        }
    }

    /**
     * 성능 리포트 생성
     */
    public String generatePerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Performance Optimization Report ===\n");
        
        // 캐시 통계
        report.append("\nCache Statistics:\n");
        report.append(String.format("  Hit Rate: %.2f%%\n", calculateCacheHitRate()));
        report.append(String.format("  Total Hits: %d\n", cacheHits.get()));
        report.append(String.format("  Total Misses: %d\n", cacheMisses.get()));
        report.append(String.format("  Cache Size: %d\n", areaCache.getCacheSize()));
        
        // 메모리 통계
        MemoryUsage memory = getMemoryUsage();
        report.append("\nMemory Statistics:\n");
        report.append(String.format("  Used: %d MB (%.2f%%)\n", 
                memory.getUsed() / 1024 / 1024, memory.getPercentage()));
        report.append(String.format("  Total: %d MB\n", memory.getTotal() / 1024 / 1024));
        report.append(String.format("  Max: %d MB\n", memory.getMax() / 1024 / 1024));
        report.append(String.format("  Optimizations: %d\n", memoryOptimizations.get()));
        
        // 스레드풀 통계
        report.append("\nThread Pool Statistics:\n");
        threadPools.forEach((name, pool) -> {
            report.append(String.format("  %s:\n", name));
            report.append(String.format("    Active: %d/%d\n", 
                    pool.getActiveCount(), pool.getPoolSize()));
            report.append(String.format("    Queue: %d\n", pool.getQueue().size()));
            report.append(String.format("    Completed: %d\n", pool.getCompletedTaskCount()));
        });
        
        // 연결 통계
        report.append("\nConnection Statistics:\n");
        report.append(String.format("  Active Connections: %d\n", activeConnections.get()));
        
        return report.toString();
    }

    /**
     * 모든 스레드풀 종료
     */
    public void shutdown() {
        log.info("Shutting down performance optimization manager");
        
        threadPools.forEach((name, pool) -> {
            log.info("Shutting down thread pool: {}", name);
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        
        performMemoryOptimization();
    }

    // 내부 클래스들
    public static class PerformanceStatus {
        private double cacheHitRate;
        private int cacheSize;
        private MemoryUsage memoryUsage;
        private int dataHolderSize;
        private int activeConnections;
        private final List<ThreadPoolStatus> threadPoolStatuses = new ArrayList<>();

        // Getter/Setter
        public double getCacheHitRate() { return cacheHitRate; }
        public void setCacheHitRate(double cacheHitRate) { this.cacheHitRate = cacheHitRate; }
        public int getCacheSize() { return cacheSize; }
        public void setCacheSize(int cacheSize) { this.cacheSize = cacheSize; }
        public MemoryUsage getMemoryUsage() { return memoryUsage; }
        public void setMemoryUsage(MemoryUsage memoryUsage) { this.memoryUsage = memoryUsage; }
        public int getDataHolderSize() { return dataHolderSize; }
        public void setDataHolderSize(int dataHolderSize) { this.dataHolderSize = dataHolderSize; }
        public int getActiveConnections() { return activeConnections; }
        public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }
        public List<ThreadPoolStatus> getThreadPoolStatuses() { return threadPoolStatuses; }
    }

    public static class MemoryUsage {
        private long total;
        private long used;
        private long max;
        private double percentage;

        // Getter/Setter
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getUsed() { return used; }
        public void setUsed(long used) { this.used = used; }
        public long getMax() { return max; }
        public void setMax(long max) { this.max = max; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
    }

    public static class ThreadPoolStatus {
        private String name;
        private int activeCount;
        private int poolSize;
        private int queueSize;
        private long completedTaskCount;

        // Getter/Setter
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getActiveCount() { return activeCount; }
        public void setActiveCount(int activeCount) { this.activeCount = activeCount; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
        public int getQueueSize() { return queueSize; }
        public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
        public long getCompletedTaskCount() { return completedTaskCount; }
        public void setCompletedTaskCount(long completedTaskCount) { this.completedTaskCount = completedTaskCount; }
    }
}