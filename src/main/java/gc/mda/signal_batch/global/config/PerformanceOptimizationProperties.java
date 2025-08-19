package gc.mda.signal_batch.global.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 성능 최적화 설정
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vessel.batch.optimization")
public class PerformanceOptimizationProperties {
    
    private boolean enabled = true;
    private boolean dynamicChunkSizing = true;
    private boolean memoryOptimization = true;
    private boolean cacheOptimization = true;
    private boolean threadPoolOptimization = true;
    
    private ChunkSettings chunk = new ChunkSettings();
    private MemorySettings memory = new MemorySettings();
    private CacheSettings cache = new CacheSettings();
    
    @Data
    public static class ChunkSettings {
        private int minSize = 1000;
        private int maxSize = 20000;
        private double adjustmentFactor = 0.2;
    }
    
    @Data
    public static class MemorySettings {
        private int warningThreshold = 70;
        private int criticalThreshold = 85;
        private int optimizationThreshold = 80;
    }
    
    @Data
    public static class CacheSettings {
        private int minHitRate = 70;
        private int areaBoundarySize = 5000;
    }
}