package gc.mda.signal_batch.batch.writer;

import gc.mda.signal_batch.domain.vessel.model.VesselLatestPosition;
import gc.mda.signal_batch.batch.processor.AreaStatisticsProcessor.AreaStatistics;
import gc.mda.signal_batch.global.util.ConcurrentUpdateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;



@Slf4j
@Configuration
@RequiredArgsConstructor
public class UpsertWriter {

    @Qualifier("queryDataSource")
    private final DataSource queryDataSource;

    private final ConcurrentUpdateManager concurrentUpdateManager;

    @Value("${vessel.batch.writer.use-advisory-lock:false}")
    private boolean useAdvisoryLock;

    @Value("${vessel.batch.writer.parallel-threads:4}")
    private int parallelThreads;

    private static final ExecutorService executorService = new ThreadPoolExecutor(
            4, 8,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // shutdown hook 추가
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down executor service...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }));
    }
    
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 최신 위치 Writer - Advisory Lock 사용
     */
    @Bean
    public ItemWriter<VesselLatestPosition> latestPositionWriter() {
        if (useAdvisoryLock) {
            return new ItemWriter<VesselLatestPosition>() {
                @Override
                public void write(Chunk<? extends VesselLatestPosition> chunk) throws Exception {
                    List<VesselLatestPosition> items = new ArrayList<>(chunk.getItems());

                    // 병렬 처리를 위한 분할
                    int batchSize = Math.max(1, items.size() / parallelThreads);
                    List<CompletableFuture<Void>> futures = new ArrayList<>();

                    for (int i = 0; i < items.size(); i += batchSize) {
                        int endIndex = Math.min(i + batchSize, items.size());
                        List<VesselLatestPosition> batch = items.subList(i, endIndex);

                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            for (VesselLatestPosition position : batch) {
                                try {
                                    concurrentUpdateManager.updateLatestPositionWithLock(position);
                                } catch (Exception e) {
                                    log.error("Failed to update position: {}", position.getTargetId(), e);
                                }
                            }
                        }, executorService);

                        futures.add(future);
                    }

                    // 모든 작업 완료 대기
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(5, TimeUnit.MINUTES);

                    log.debug("Updated {} vessel positions", items.size());
                }
            };
        } else {
            // 기존 방식 (Batch Update)
            return defaultLatestPositionWriter();
        }
    }

    /**
     * 기본 Batch Writer
     */
    private JdbcBatchItemWriter<VesselLatestPosition> defaultLatestPositionWriter() {
        return customLatestPositionWriter();
    }
    
    /**
     * Custom Writer - UPDATE 0건도 정상 처리
     */
    private JdbcBatchItemWriter<VesselLatestPosition> customLatestPositionWriter() {
        String sql = """
            INSERT INTO signal.t_vessel_latest_position (
                sig_src_cd, target_id, lat, lon, geom,
                sog, cog, heading, ship_nm, ship_ty,
                last_update, update_count, created_at
            ) VALUES (
                :sigSrcCd, :targetId, :lat, :lon, 
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326),
                :sog, :cog, :heading, :shipNm, :shipTy,
                :lastUpdate, 1, CURRENT_TIMESTAMP
            )
            ON CONFLICT (sig_src_cd, target_id) DO UPDATE SET
                lat = EXCLUDED.lat,
                lon = EXCLUDED.lon,
                geom = EXCLUDED.geom,
                sog = EXCLUDED.sog,
                cog = EXCLUDED.cog,
                heading = EXCLUDED.heading,
                ship_nm = COALESCE(EXCLUDED.ship_nm, t_vessel_latest_position.ship_nm),
                ship_ty = COALESCE(EXCLUDED.ship_ty, t_vessel_latest_position.ship_ty),
                last_update = EXCLUDED.last_update,
                update_count = t_vessel_latest_position.update_count + 1
            WHERE EXCLUDED.last_update > t_vessel_latest_position.last_update
        """;

        JdbcBatchItemWriter<VesselLatestPosition> writer = new JdbcBatchItemWriter<VesselLatestPosition>() {
            @Override
            public void write(Chunk<? extends VesselLatestPosition> chunk) throws Exception {
                // assertUpdates 비활성화로 UPDATE 0건도 허용
                this.setAssertUpdates(false);
                super.write(chunk);
            }
        };
        
        writer.setDataSource(queryDataSource);
        writer.setSql(sql);
        writer.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>());
        writer.afterPropertiesSet();
        
        return writer;
    }

    /**
     * 구역 통계 Writer
     */
    @Bean
    public ItemWriter<List<AreaStatistics>> areaStatisticsWriter() {
        return new ItemWriter<List<AreaStatistics>>() {
            @Override
            public void write(Chunk<? extends List<AreaStatistics>> chunk) throws Exception {
                // 중복 제거를 위한 Map 사용
                Map<String, AreaStatistics> uniqueStats = new HashMap<>();
                
                for (List<AreaStatistics> batch : chunk.getItems()) {
                    for (AreaStatistics stat : batch) {
                        String key = stat.getAreaId() + "_" + stat.getTimeBucket();
                        // 중복된 경우 나중 데이터로 덮어쓰기
                        uniqueStats.put(key, stat);
                    }
                }
                
                List<AreaStatistics> allStats = new ArrayList<>(uniqueStats.values());
                
                if (allStats.isEmpty()) {
                    return;
                }
                
                // 배치를 더 작은 단위로 분할
                int batchSize = 500;
                JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
                jdbcTemplate.setQueryTimeout(60); // 60초 타임아웃
                
                for (int i = 0; i < allStats.size(); i += batchSize) {
                    int endIndex = Math.min(i + batchSize, allStats.size());
                    List<AreaStatistics> subBatch = allStats.subList(i, endIndex);
                    
                    String sql = """
                        INSERT INTO signal.t_area_statistics (
                            area_id, time_bucket, vessel_count, in_count, out_count,
                            transit_vessels, stationary_vessels, avg_sog, created_at
                        ) VALUES (
                            ?, ?, ?, ?, ?,
                            ?::jsonb, ?::jsonb, ?, CURRENT_TIMESTAMP
                        )
                        ON CONFLICT (area_id, time_bucket) DO UPDATE SET
                            vessel_count = EXCLUDED.vessel_count,
                            in_count = EXCLUDED.in_count,
                            out_count = EXCLUDED.out_count,
                            transit_vessels = EXCLUDED.transit_vessels,
                            stationary_vessels = EXCLUDED.stationary_vessels,
                            avg_sog = EXCLUDED.avg_sog
                    """;
                    
                    List<Object[]> batchArgs = new ArrayList<>();
                    for (AreaStatistics stats : subBatch) {
                        batchArgs.add(new Object[]{
                            stats.getAreaId(),
                            java.sql.Timestamp.valueOf(stats.getTimeBucket()),
                            stats.getVesselCount(),
                            stats.getInCount(),
                            stats.getOutCount(),
                            objectMapper.writeValueAsString(stats.getTransitVessels()),
                            objectMapper.writeValueAsString(stats.getStationaryVessels()),
                            stats.getAvgSog()
                        });
                    }
                    
                    try {
                        jdbcTemplate.batchUpdate(sql, batchArgs);
                        log.debug("Updated {} area statistics records", subBatch.size());
                    } catch (Exception e) {
                        log.error("Failed to update batch of {} area statistics", subBatch.size(), e);
                        throw e;
                    }
                }
                
                log.info("Total updated {} area statistics records", allStats.size());
            }
        };
    }

    /**
     * 리소스 정리
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}