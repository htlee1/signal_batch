package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import gc.mda.signal_batch.batch.processor.VesselTrackProcessor;
import gc.mda.signal_batch.batch.processor.AbnormalTrackDetector;
import gc.mda.signal_batch.batch.processor.AbnormalTrackDetector.AbnormalDetectionResult;
import gc.mda.signal_batch.batch.reader.InMemoryVesselTrackDataReader;
import gc.mda.signal_batch.global.util.VesselTrackDataHolder;
import gc.mda.signal_batch.global.util.TrackClippingUtils;
import gc.mda.signal_batch.batch.writer.VesselTrackBulkWriter;
import gc.mda.signal_batch.batch.writer.AbnormalTrackWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import org.springframework.batch.item.Chunk;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;


@Slf4j
@Configuration
@RequiredArgsConstructor
public class VesselTrackStepConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource queryDataSource;
    private final VesselTrackProcessor vesselTrackProcessor;
    private final VesselTrackDataHolder vesselTrackDataHolder;
    private final VesselTrackBulkWriter vesselTrackBulkWriter;
    private final TrackClippingUtils trackClippingUtils;
    @SuppressWarnings("unused")
    private final AbnormalTrackDetector abnormalTrackDetector;
    private final AbnormalTrackWriter abnormalTrackWriter;

    @Value("${vessel.batch.chunk-size:1000}")
    private int chunkSize;
    
    @PostConstruct
    public void init() {
        // 5분 Job의 이름을 명시적으로 설정
        abnormalTrackWriter.setJobName("vesselTrackAggregationJob");
        log.info("AbnormalTrackWriter initialized with job name: vesselTrackAggregationJob");
    }

    @Bean
    public Step vesselTrackStep() {
        return new StepBuilder("vesselTrackStep", jobRepository)
                .<List<VesselData>, List<VesselTrack>>chunk(chunkSize, transactionManager)
                .reader(trackDataReader())
                .processor(trackProcessorWithSimpleFilter())
                .writer(compositeTrackWriter())
                .build();
    }

    @Bean
    @StepScope
    public InMemoryVesselTrackDataReader trackDataReader() {
        return new InMemoryVesselTrackDataReader(vesselTrackDataHolder, chunkSize);
    }

    @Bean
    @StepScope
    public ItemProcessor<List<VesselData>, List<VesselTrack>> trackProcessorWithSimpleFilter() {
        return items -> {
            // 1. 기본 처리
            List<VesselTrack> tracks = vesselTrackProcessor.process(items);
            if (tracks == null || tracks.isEmpty()) {
                return null;
            }
            
            // 2. 강화된 비정상 궤적 필터링
            List<VesselTrack> filteredTracks = new ArrayList<>();
            for (VesselTrack track : tracks) {
                boolean isAbnormal = false;
                
                // 선박/항공기 구분
                boolean isAircraft = "000019".equals(track.getSigSrcCd());
                double speedLimit = isAircraft ? 300.0 : 100.0;  // 항공기 300, 선박 100
                double distanceLimit = isAircraft ? 30.0 : 10.0; // 항공기 30nm, 선박 10nm
                
                // 평균속도 체크
                if (track.getAvgSpeed() != null && track.getAvgSpeed().doubleValue() >= speedLimit) {
                    isAbnormal = true;
                }
                
                // 5분간 이동거리 체크
                if (track.getDistanceNm() != null && track.getDistanceNm().doubleValue() >= distanceLimit) {
                    isAbnormal = true;
                }
                
                if (isAbnormal) {
                    log.warn("5분 비정상 궤적 감지: vessel={}, avg_speed={}, distance={}", 
                        track.getVesselKey(), track.getAvgSpeed(), track.getDistanceNm());
                    saveAbnormalTrack(track);
                } else {
                    filteredTracks.add(track);
                }
            }
            
            return filteredTracks.isEmpty() ? null : filteredTracks;
        };
    }
    
    private void saveAbnormalTrack(VesselTrack track) {
        try {
            // Job 이름 설정
            abnormalTrackWriter.setJobName("vesselTrackAggregationJob");
            
            List<AbnormalTrackDetector.AbnormalSegment> segments = new ArrayList<>();
            Map<String, Object> details = new HashMap<>();
            details.put("avgSpeed", track.getAvgSpeed());
            details.put("distanceNm", track.getDistanceNm());
            details.put("timeBucket", track.getTimeBucket());
            
            // 선박/항공기 구분
            boolean isAircraft = "000019".equals(track.getSigSrcCd());
            double speedLimit = isAircraft ? 300.0 : 100.0;
            double distanceLimit = isAircraft ? 30.0 : 10.0;
            
            // 비정상 유형 결정
            String abnormalType = "abnormal_5min";
            double actualValue = 0.0;
            double threshold = 0.0;
            String description = "";
            
            if (track.getAvgSpeed() != null && track.getAvgSpeed().doubleValue() >= speedLimit) {
                abnormalType = "extreme_avg_speed_5min";
                actualValue = track.getAvgSpeed().doubleValue();
                threshold = speedLimit;
                description = String.format("5분 비정상 평균속도: %.1f knots", actualValue);
            } else if (track.getDistanceNm() != null && track.getDistanceNm().doubleValue() >= distanceLimit) {
                abnormalType = "extreme_distance_5min";
                actualValue = track.getDistanceNm().doubleValue();
                threshold = distanceLimit;
                description = String.format("5분 비정상 이동거리: %.1f nm", actualValue);
            }
            
            segments.add(AbnormalTrackDetector.AbnormalSegment.builder()
                .type(abnormalType)
                .startIndex(0)
                .endIndex(track.getPointCount() - 1)
                .actualValue(actualValue)
                .threshold(threshold)
                .description(description)
                .details(details)
                .build());
            
            AbnormalDetectionResult result = AbnormalTrackDetector.AbnormalDetectionResult.builder()
                .originalTrack(track)
                .correctedTrack(null)
                .abnormalSegments(segments)
                .hasAbnormalities(true)
                .build();
            
            abnormalTrackWriter.write(new Chunk<>(Arrays.asList(result)));
        } catch (Exception e) {
            log.error("Failed to save 5min abnormal track", e);
        }
    }

    // CompositeItemWriter로 3개 테이블에 동시 저장
    @Bean
    @StepScope
    public ItemWriter<List<VesselTrack>> compositeTrackWriter() {
        CompositeItemWriter<List<VesselTrack>> compositeWriter = new CompositeItemWriter<>();
        compositeWriter.setDelegates(Arrays.asList(
                vesselTrackWriter(),
                gridTrackWriter(),
                areaTrackWriter()
        ));
        return compositeWriter;
    }

    @Bean
    @StepScope
    public ItemWriter<List<VesselTrack>> vesselTrackWriter() {
        return vesselTrackBulkWriter;
    }

    @Bean
    @StepScope
    public ItemWriter<List<VesselTrack>> gridTrackWriter() {
        return chunk -> {
            // 각 track을 해구별로 분할
            List<VesselTrack> allClippedTracks = new ArrayList<>();

            for (List<VesselTrack> trackList : chunk.getItems()) {
                for (VesselTrack track : trackList) {
                    List<VesselTrack> clippedTracks = trackClippingUtils.clipTracksByHaegu(track);
                    allClippedTracks.addAll(clippedTracks);
                }
            }

            if (allClippedTracks.isEmpty()) {
                log.debug("No tracks to write to grid table after clipping");
                return;
            }

            String sql = """
                INSERT INTO signal.t_grid_vessel_tracks (
                    haegu_no, sig_src_cd, target_id, time_bucket,
                    distance_nm, avg_speed, point_count, entry_time, exit_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (haegu_no, sig_src_cd, target_id, time_bucket) DO NOTHING
            """;

            List<Object[]> args = allClippedTracks.stream()
                    .map(track -> new Object[] {
                            track.getHaeguNo(),
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getPointCount(),
                            track.getEntryTime() != null ? Timestamp.valueOf(track.getEntryTime()) : null,
                            track.getExitTime() != null ? Timestamp.valueOf(track.getExitTime()) : null
                    })
                    .collect(Collectors.toList());

            int[] results = new JdbcTemplate(queryDataSource).batchUpdate(sql, args);
            log.info("Inserted {} clipped records to t_grid_vessel_tracks", results.length);
        };
    }

    @Bean
    @StepScope
    public ItemWriter<List<VesselTrack>> areaTrackWriter() {
        return chunk -> {
            // 각 track을 area별로 분할
            List<VesselTrack> allClippedTracks = new ArrayList<>();

            for (List<VesselTrack> trackList : chunk.getItems()) {
                for (VesselTrack track : trackList) {
                    List<VesselTrack> clippedTracks = trackClippingUtils.clipTracksByArea(track);
                    allClippedTracks.addAll(clippedTracks);
                }
            }

            if (allClippedTracks.isEmpty()) {
                log.debug("No tracks to write to area table after clipping");
                return;
            }

            String sql = """
                INSERT INTO signal.t_area_vessel_tracks (
                    area_id, sig_src_cd, target_id, time_bucket,
                    distance_nm, avg_speed, point_count, metrics
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (area_id, sig_src_cd, target_id, time_bucket) DO NOTHING
            """;

            List<Object[]> args = allClippedTracks.stream()
                    .map(track -> new Object[] {
                            track.getAreaId(),
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getPointCount(),
                            "{}"
                    })
                    .collect(Collectors.toList());

            int[] results = new JdbcTemplate(queryDataSource).batchUpdate(sql, args);
            log.info("Inserted {} clipped records to t_area_vessel_tracks", results.length);
        };
    }

    // 집계 스텝들 (변경 없음)
    @Bean
    public Step gridTrackSummaryStep() {
        return new StepBuilder("gridTrackSummaryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);

                    String sql = """
                        INSERT INTO signal.t_grid_tracks_summary 
                        (haegu_no, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list)
                        SELECT 
                            haegu_no,
                            time_bucket,
                            COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as total_vessels,
                            SUM(distance_nm) as total_distance_nm,
                            AVG(avg_speed) as avg_speed,
                            jsonb_agg(jsonb_build_object(
                                'sig_src_cd', sig_src_cd,
                                'target_id', target_id,
                                'distance_nm', distance_nm,
                                'avg_speed', avg_speed
                            )) as vessel_list
                        FROM signal.t_grid_vessel_tracks
                        WHERE time_bucket = ?
                        GROUP BY haegu_no, time_bucket
                        ON CONFLICT (haegu_no, time_bucket) 
                        DO UPDATE SET
                            total_vessels = EXCLUDED.total_vessels,
                            total_distance_nm = EXCLUDED.total_distance_nm,
                            avg_speed = EXCLUDED.avg_speed,
                            vessel_list = EXCLUDED.vessel_list
                    """;

                    String timeBucketStr = (String) chunkContext.getStepContext()
                            .getJobParameters().get("timeBucket");
                    Timestamp timeBucket = Timestamp.valueOf(timeBucketStr);

                    int updated = jdbcTemplate.update(sql, timeBucket);
                    log.info("Updated {} grid track summaries for time_bucket: {}", updated, timeBucket);

                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step areaTrackSummaryStep() {
        return new StepBuilder("areaTrackSummaryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);

                    String sql = """
                        INSERT INTO signal.t_area_tracks_summary 
                        (area_id, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list)
                        SELECT 
                            area_id,
                            time_bucket,
                            COUNT(DISTINCT CONCAT(sig_src_cd, '_', target_id)) as total_vessels,
                            SUM(distance_nm) as total_distance_nm,
                            AVG(avg_speed) as avg_speed,
                            jsonb_agg(jsonb_build_object(
                                'sig_src_cd', sig_src_cd,
                                'target_id', target_id,
                                'distance_nm', distance_nm,
                                'avg_speed', avg_speed
                            )) as vessel_list
                        FROM signal.t_area_vessel_tracks
                        WHERE time_bucket = ?
                        GROUP BY area_id, time_bucket
                        ON CONFLICT (area_id, time_bucket) 
                        DO UPDATE SET
                            total_vessels = EXCLUDED.total_vessels,
                            total_distance_nm = EXCLUDED.total_distance_nm,
                            avg_speed = EXCLUDED.avg_speed,
                            vessel_list = EXCLUDED.vessel_list
                    """;

                    String timeBucketStr = (String) chunkContext.getStepContext()
                            .getJobParameters().get("timeBucket");
                    Timestamp timeBucket = Timestamp.valueOf(timeBucketStr);

                    int updated = jdbcTemplate.update(sql, timeBucket);
                    log.info("Updated {} area track summaries for time_bucket: {}", updated, timeBucket);

                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}