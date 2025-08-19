package gc.mda.signal_batch.batch.processor;

import gc.mda.signal_batch.domain.gis.model.TileStatistics;
import gc.mda.signal_batch.domain.vessel.model.VesselData;
import gc.mda.signal_batch.global.util.HaeguGeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;


@Slf4j
@Configuration
@RequiredArgsConstructor
public class TileAggregationProcessor {

    private final HaeguGeoUtils geoUtils;

    /**
     * 타일 레벨과 시간 버킷에 따른 배치 프로세서 생성
     */
    public ItemProcessor<List<VesselData>, List<TileStatistics>> batchProcessor(
            int tileLevel, int timeBucketMinutes) {
        
        return items -> {
            if (items == null || items.isEmpty()) {
                return null;
            }

            Map<String, TileStatistics> tileMap = new HashMap<>();

            for (VesselData item : items) {
                if (!item.isValidPosition()) {
                    continue;
                }

                LocalDateTime bucket = item.getMessageTime()
                        .truncatedTo(ChronoUnit.MINUTES)
                        .withMinute((item.getMessageTime().getMinute() / timeBucketMinutes) * timeBucketMinutes);

                // 요청된 레벨에 따라 처리
                if (tileLevel >= 0) {
                    // Level 0 (대해구) 처리
                    HaeguGeoUtils.HaeguTileInfo level0Info = geoUtils.getHaeguTileInfo(
                        item.getLat(), item.getLon(), 0
                    );
                    
                    if (level0Info != null) {
                        String haeguKey = level0Info.tileId + "_" + bucket.toString();
                        
                        TileStatistics haeguStats = tileMap.computeIfAbsent(haeguKey,
                                k -> TileStatistics.builder()
                                        .tileId(level0Info.tileId)
                                        .tileLevel(0)
                                        .timeBucket(bucket)
                                        .uniqueVessels(new HashMap<>())
                                        .totalPoints(0L)
                                        .avgSog(BigDecimal.ZERO)
                                        .maxSog(BigDecimal.ZERO)
                                        .build()
                        );
                        haeguStats.addVesselData(item);
                    }
                }

                if (tileLevel >= 1) {
                    // Level 1 (소해구) 처리
                    HaeguGeoUtils.HaeguTileInfo level1Info = geoUtils.getHaeguTileInfo(
                        item.getLat(), item.getLon(), 1
                    );
                    
                    if (level1Info != null && level1Info.sohaeguNo != null) {
                        String subKey = level1Info.tileId + "_" + bucket.toString();
                        
                        TileStatistics subStats = tileMap.computeIfAbsent(subKey,
                                k -> TileStatistics.builder()
                                        .tileId(level1Info.tileId)
                                        .tileLevel(1)
                                        .timeBucket(bucket)
                                        .uniqueVessels(new HashMap<>())
                                        .totalPoints(0L)
                                        .avgSog(BigDecimal.ZERO)
                                        .maxSog(BigDecimal.ZERO)
                                        .build()
                        );
                        subStats.addVesselData(item);
                    }
                }
            }

            // 각 타일별로 밀도 계산
            tileMap.values().forEach(this::calculateDensity);

            return new ArrayList<>(tileMap.values());
        };
    }

    @Bean
    @StepScope
    public ItemProcessor<List<VesselData>, List<TileStatistics>> tileAggregationBatchProcessor(
            @Value("#{jobParameters['timeBucketMinutes']}") Integer timeBucketMinutes) {

        final int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;

        return items -> {
            if (items == null || items.isEmpty()) {
                return null;
            }

            Map<String, TileStatistics> tileMap = new HashMap<>();

            for (VesselData item : items) {
                if (!item.isValidPosition()) {
                    continue;
                }

                LocalDateTime bucket = item.getMessageTime()
                        .truncatedTo(ChronoUnit.MINUTES)
                        .withMinute((item.getMessageTime().getMinute() / bucketMinutes) * bucketMinutes);

                // 1. 대해구 레벨(Level 0) 처리
                HaeguGeoUtils.HaeguTileInfo level0Info = geoUtils.getHaeguTileInfo(
                    item.getLat(), item.getLon(), 0
                );
                
                if (level0Info != null) {
                    String haeguKey = level0Info.tileId + "_" + bucket.toString();
                    
                    TileStatistics haeguStats = tileMap.computeIfAbsent(haeguKey,
                            k -> TileStatistics.builder()
                                    .tileId(level0Info.tileId)
                                    .tileLevel(0)  // 대해구는 레벨 0
                                    .timeBucket(bucket)
                                    .uniqueVessels(new HashMap<>())
                                    .totalPoints(0L)
                                    .avgSog(BigDecimal.ZERO)
                                    .maxSog(BigDecimal.ZERO)
                                    .build()
                    );
                    haeguStats.addVesselData(item);
                }

                // 2. 소해구 레벨(Level 1) 처리
                HaeguGeoUtils.HaeguTileInfo level1Info = geoUtils.getHaeguTileInfo(
                    item.getLat(), item.getLon(), 1
                );
                
                if (level1Info != null && level1Info.sohaeguNo != null) {
                    String subKey = level1Info.tileId + "_" + bucket.toString();
                    
                    TileStatistics subStats = tileMap.computeIfAbsent(subKey,
                            k -> TileStatistics.builder()
                                    .tileId(level1Info.tileId)
                                    .tileLevel(1)  // 소해구는 레벨 1
                                    .timeBucket(bucket)
                                    .uniqueVessels(new HashMap<>())
                                    .totalPoints(0L)
                                    .avgSog(BigDecimal.ZERO)
                                    .maxSog(BigDecimal.ZERO)
                                    .build()
                    );
                    subStats.addVesselData(item);
                }
            }

            // 각 타일별로 밀도 계산
            tileMap.values().forEach(stats -> {
                calculateDensity(stats);
            });

            return new ArrayList<>(tileMap.values());
        };
    }

    @Bean
    @StepScope
    public ItemProcessor<VesselData, List<TileStatistics>> singleItemProcessor(
            @Value("#{jobParameters['tileLevel']}") Integer tileLevel,
            @Value("#{jobParameters['timeBucketMinutes']}") Integer timeBucketMinutes) {

        final int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;
        final int maxLevel = (tileLevel != null) ? tileLevel : 1;

        Map<String, TileStatistics> accumulator = new HashMap<>();

        return item -> {
            if (!item.isValidPosition()) {
                return null;
            }

            LocalDateTime bucket = item.getMessageTime()
                    .truncatedTo(ChronoUnit.MINUTES)
                    .withMinute((item.getMessageTime().getMinute() / bucketMinutes) * bucketMinutes);

            List<TileStatistics> result = new ArrayList<>();

            // Level 0 (대해구)
            if (maxLevel >= 0) {
                HaeguGeoUtils.HaeguTileInfo level0Info = geoUtils.getHaeguTileInfo(
                    item.getLat(), item.getLon(), 0
                );
                
                if (level0Info != null) {
                    String key = level0Info.tileId + "_" + bucket.toString();
                    TileStatistics stats = accumulator.computeIfAbsent(key,
                            k -> TileStatistics.builder()
                                    .tileId(level0Info.tileId)
                                    .tileLevel(0)
                                    .timeBucket(bucket)
                                    .uniqueVessels(new HashMap<>())
                                    .totalPoints(0L)
                                    .avgSog(BigDecimal.ZERO)
                                    .maxSog(BigDecimal.ZERO)
                                    .build()
                    );
                    stats.addVesselData(item);

                    // 일정 개수가 쌓이면 출력
                    if (stats.getTotalPoints() % 1000 == 0) {
                        calculateDensity(stats);
                        result.add(stats);
                    }
                }
            }

            // Level 1 (소해구)
            if (maxLevel >= 1) {
                HaeguGeoUtils.HaeguTileInfo level1Info = geoUtils.getHaeguTileInfo(
                    item.getLat(), item.getLon(), 1
                );
                
                if (level1Info != null && level1Info.sohaeguNo != null) {
                    String key = level1Info.tileId + "_" + bucket.toString();
                    TileStatistics stats = accumulator.computeIfAbsent(key,
                            k -> TileStatistics.builder()
                                    .tileId(level1Info.tileId)
                                    .tileLevel(1)
                                    .timeBucket(bucket)
                                    .uniqueVessels(new HashMap<>())
                                    .totalPoints(0L)
                                    .avgSog(BigDecimal.ZERO)
                                    .maxSog(BigDecimal.ZERO)
                                    .build()
                    );
                    stats.addVesselData(item);

                    // 일정 개수가 쌓이면 출력
                    if (stats.getTotalPoints() % 1000 == 0) {
                        calculateDensity(stats);
                        result.add(stats);
                    }
                }
            }

            return result.isEmpty() ? null : result;
        };
    }

    /**
     * 타일의 선박 밀도 계산
     */
    private void calculateDensity(TileStatistics stats) {
        if (stats.getVesselCount() == null || stats.getVesselCount() == 0) {
            stats.setVesselDensity(BigDecimal.ZERO);
            return;
        }

        // 타일 면적 가져오기 (km²)
        double tileArea = geoUtils.getTileArea(stats.getTileId());
        
        if (tileArea > 0) {
            // 밀도 = 선박 수 / 면적
            BigDecimal density = BigDecimal.valueOf(stats.getVesselCount())
                    .divide(BigDecimal.valueOf(tileArea), 6, RoundingMode.HALF_UP);
            stats.setVesselDensity(density);
        } else {
            stats.setVesselDensity(BigDecimal.ZERO);
        }
    }
}