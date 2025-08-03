package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.model.TileStatistics;
import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.util.HaeguGeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전체 데이터를 누적하여 집계하는 프로세서
 * Step 실행 중 모든 데이터를 메모리에 누적하고, Step 완료 시 한 번에 출력
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class AccumulatingTileProcessor implements ItemProcessor<VesselData, TileStatistics> {

    private final HaeguGeoUtils geoUtils;

    @Value("#{jobParameters['tileLevel']}")
    private Integer tileLevel;

    @Value("#{jobParameters['timeBucketMinutes']}")
    private Integer timeBucketMinutes;

    // 전체 집계를 위한 누적 맵
    private final Map<String, TileStatistics> accumulator = new ConcurrentHashMap<>();
    
    // 처리된 레코드 수 추적
    private long processedCount = 0;
    private long skippedCount = 0;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        int level = (tileLevel != null) ? tileLevel : 1;
        int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;
        
        log.info("Starting AccumulatingTileProcessor - tileLevel: {}, timeBucket: {} minutes", 
                level, bucketMinutes);
        
        // 초기화
        accumulator.clear();
        processedCount = 0;
        skippedCount = 0;
    }

    @Override
    public TileStatistics process(VesselData item) throws Exception {
        if (item == null || !item.isValidPosition()) {
            skippedCount++;
            return null;
        }

        processedCount++;
        
        int level = (tileLevel != null) ? tileLevel : 1;
        int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;

        LocalDateTime bucket = item.getMessageTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .withMinute((item.getMessageTime().getMinute() / bucketMinutes) * bucketMinutes);

        // Level 0 (대해구) 처리
        if (level >= 0) {
            processLevel0(item, bucket);
        }

        // Level 1 (소해구) 처리
        if (level >= 1) {
            processLevel1(item, bucket);
        }

        // 10000건마다 진행 상황 로그
        if (processedCount % 10000 == 0) {
            log.debug("Processed {} records, accumulated {} tiles", 
                    processedCount, accumulator.size());
        }

        // null 반환 - 실제 출력은 AfterStep에서 수행
        return null;
    }

    private void processLevel0(VesselData item, LocalDateTime bucket) {
        HaeguGeoUtils.HaeguTileInfo level0Info = geoUtils.getHaeguTileInfo(
            item.getLat(), item.getLon(), 0
        );
        
        if (level0Info != null) {
            String key = generateKey(level0Info.tileId, 0, bucket);
            
            accumulator.compute(key, (k, existing) -> {
                if (existing == null) {
                    existing = TileStatistics.builder()
                            .tileId(level0Info.tileId)
                            .tileLevel(0)
                            .timeBucket(bucket)
                            .uniqueVessels(new HashMap<>())
                            .totalPoints(0L)
                            .avgSog(BigDecimal.ZERO)
                            .maxSog(BigDecimal.ZERO)
                            .build();
                }
                existing.addVesselData(item);
                return existing;
            });
        }
    }

    private void processLevel1(VesselData item, LocalDateTime bucket) {
        HaeguGeoUtils.HaeguTileInfo level1Info = geoUtils.getHaeguTileInfo(
            item.getLat(), item.getLon(), 1
        );
        
        if (level1Info != null && level1Info.sohaeguNo != null) {
            String key = generateKey(level1Info.tileId, 1, bucket);
            
            accumulator.compute(key, (k, existing) -> {
                if (existing == null) {
                    existing = TileStatistics.builder()
                            .tileId(level1Info.tileId)
                            .tileLevel(1)
                            .timeBucket(bucket)
                            .uniqueVessels(new HashMap<>())
                            .totalPoints(0L)
                            .avgSog(BigDecimal.ZERO)
                            .maxSog(BigDecimal.ZERO)
                            .build();
                }
                existing.addVesselData(item);
                return existing;
            });
        }
    }

    private String generateKey(String tileId, int tileLevel, LocalDateTime timeBucket) {
        return String.format("%s|%d|%s", tileId, tileLevel, timeBucket);
    }

    @AfterStep
    public void afterStep(StepExecution stepExecution) {
        log.info("AccumulatingTileProcessor completed - processed: {}, skipped: {}, tiles: {}", 
                processedCount, skippedCount, accumulator.size());

        // 밀도 계산
        accumulator.values().forEach(this::calculateDensity);

        // 메트릭 저장
        stepExecution.getExecutionContext().putLong("totalProcessed", processedCount);
        stepExecution.getExecutionContext().putLong("totalSkipped", skippedCount);
        stepExecution.getExecutionContext().putInt("totalTiles", accumulator.size());
        
        // 이 위치에서 바로 DB에 저장하면 안됨 - StepListener에서 처리해야 함
        log.info("Accumulated {} tiles ready for writing", accumulator.size());
    }

    private void calculateDensity(TileStatistics stats) {
        if (stats.getVesselCount() == null || stats.getVesselCount() == 0) {
            stats.setVesselDensity(BigDecimal.ZERO);
            return;
        }

        double tileArea = geoUtils.getTileArea(stats.getTileId());
        
        if (tileArea > 0) {
            BigDecimal density = BigDecimal.valueOf(stats.getVesselCount())
                    .divide(BigDecimal.valueOf(tileArea), 6, BigDecimal.ROUND_HALF_UP);
            stats.setVesselDensity(density);
        } else {
            stats.setVesselDensity(BigDecimal.ZERO);
        }
    }

    /**
     * 누적된 결과 반환 (테스트용)
     */
    public List<TileStatistics> getAccumulatedResults() {
        log.info("[AccumulatingTileProcessor] getAccumulatedResults called - size: {}", accumulator.size());
        return new ArrayList<>(accumulator.values());
    }

    /**
     * 누적 데이터 초기화
     */
    public void clear() {
        accumulator.clear();
        processedCount = 0;
        skippedCount = 0;
    }
}
