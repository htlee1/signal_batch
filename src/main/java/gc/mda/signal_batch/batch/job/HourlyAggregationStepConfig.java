package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import gc.mda.signal_batch.batch.processor.HourlyTrackProcessor;
import gc.mda.signal_batch.batch.processor.HourlyTrackProcessorWithAbnormalDetection;
import gc.mda.signal_batch.batch.processor.AbnormalTrackDetector;
import gc.mda.signal_batch.batch.processor.AbnormalTrackDetector.AbnormalDetectionResult;
import gc.mda.signal_batch.batch.writer.VesselTrackBulkWriter;
import gc.mda.signal_batch.batch.writer.AbnormalTrackWriter;
import gc.mda.signal_batch.batch.writer.CompositeTrackWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HourlyAggregationStepConfig {

    private final JobRepository jobRepository;
    
    @Qualifier("queryDataSource")
    private final DataSource queryDataSource;
    
    @Qualifier("queryTransactionManager")
    private final PlatformTransactionManager transactionManager;
    
    private final VesselTrackBulkWriter vesselTrackBulkWriter;
    private final AbnormalTrackWriter abnormalTrackWriter;
    private final AbnormalTrackDetector abnormalTrackDetector;
    
    @Value("${vessel.batch.chunk-size:5000}")
    private int chunkSize;
    
    @Bean
    public Step mergeHourlyTracksStep() {
        // 비정상 궤적 검출은 항상 활성화 (설정 파일로 제어)
        boolean detectAbnormal = true;
        
        if (detectAbnormal) {
            log.info("Building mergeHourlyTracksStep with abnormal detection enabled");
            return new StepBuilder("mergeHourlyTracksStep", jobRepository)
                    .<VesselTrack.VesselKey, AbnormalDetectionResult>chunk(chunkSize, transactionManager)
                    .reader(hourlyVesselKeyReader(null, null))
                    .processor(hourlyTrackProcessorWithAbnormalDetection())
                    .writer(hourlyCompositeTrackWriter())
                    .build();
        } else {
            log.info("Building mergeHourlyTracksStep without abnormal detection");
            return new StepBuilder("mergeHourlyTracksStep", jobRepository)
                    .<VesselTrack.VesselKey, VesselTrack>chunk(chunkSize, transactionManager)
                    .reader(hourlyVesselKeyReader(null, null))
                    .processor(hourlyTrackItemProcessor())
                    .writer(hourlyTrackWriter())
                    .build();
        }
    }
    
    @Bean
    public Step gridHourlySummaryStep() {
        return new StepBuilder("gridHourlySummaryStep", jobRepository)
                .<Integer, HourlyGridSummary>chunk(100, transactionManager)
                .reader(hourlyGridReader(null, null))
                .processor(hourlyGridProcessor())
                .writer(hourlyGridWriter(null, null))
                .build();
    }
    
    @Bean
    public Step areaHourlySummaryStep() {
        return new StepBuilder("areaHourlySummaryStep", jobRepository)
                .<String, HourlyAreaSummary>chunk(100, transactionManager)
                .reader(hourlyAreaReader(null, null))
                .processor(hourlyAreaProcessor())
                .writer(hourlyAreaWriter(null, null))
                .build();
    }
    
    @Bean
    @StepScope
    public JdbcCursorItemReader<VesselTrack.VesselKey> hourlyVesselKeyReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT sig_src_cd, target_id, date_trunc('hour', time_bucket) as hour_bucket
            FROM signal.t_vessel_tracks_5min
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY sig_src_cd, target_id, hour_bucket
        """;
        
        return new JdbcCursorItemReaderBuilder<VesselTrack.VesselKey>()
                .name("hourlyVesselKeyReader")
                .dataSource(queryDataSource)
                .sql(sql)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, start);
                    ps.setObject(2, end);
                })
                .rowMapper((rs, rowNum) -> new VesselTrack.VesselKey(
                        rs.getString("sig_src_cd"),
                        rs.getString("target_id"),
                        rs.getObject("hour_bucket", LocalDateTime.class)
                ))
                .build();
    }
    
    @Bean
    public ItemProcessor<VesselTrack.VesselKey, VesselTrack> hourlyTrackItemProcessor() {
        return new HourlyTrackProcessor(queryDataSource, new JdbcTemplate(queryDataSource));
    }
    
    @Bean
    public ItemWriter<VesselTrack> hourlyTrackWriter() {
        return items -> {
            List<VesselTrack> tracks = new ArrayList<>(items.getItems());
            vesselTrackBulkWriter.writeHourlyTracks(tracks);
        };
    }
    
    // Grid summary reader
    @Bean
    @StepScope
    public JdbcCursorItemReader<Integer> hourlyGridReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT haegu_no
            FROM signal.t_grid_vessel_tracks
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY haegu_no
        """;
        
        return new JdbcCursorItemReaderBuilder<Integer>()
                .name("hourlyGridReader")
                .dataSource(queryDataSource)
                .sql(sql)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, start);
                    ps.setObject(2, end);
                })
                .rowMapper((rs, rowNum) -> rs.getInt("haegu_no"))
                .build();
    }
    
    @Bean
    public ItemProcessor<Integer, HourlyGridSummary> hourlyGridProcessor() {
        return new ItemProcessor<Integer, HourlyGridSummary>() {
            @Override
            public HourlyGridSummary process(Integer haeguNo) throws Exception {
                HourlyGridSummary summary = new HourlyGridSummary();
                summary.haeguNo = haeguNo;
                return summary;
            }
        };
    }
    
    @Bean
    @StepScope
    public ItemWriter<HourlyGridSummary> hourlyGridWriter(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        return items -> {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            LocalDateTime hourBucket = start.withMinute(0).withSecond(0).withNano(0);
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            for (HourlyGridSummary summary : items) {
                if (summary == null) continue;
                
                String sql = """
                    INSERT INTO signal.t_grid_tracks_summary_hourly
                    (haegu_no, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list, created_at)
                    SELECT 
                        haegu_no,
                        ?::timestamp as time_bucket,
                        COUNT(DISTINCT sig_src_cd || '_' || target_id) as total_vessels,
                        SUM(distance_nm) as total_distance_nm,
                        AVG(avg_speed) as avg_speed,
                        jsonb_agg(DISTINCT jsonb_build_object(
                            'sig_src_cd', sig_src_cd,
                            'target_id', target_id,
                            'distance_nm', distance_nm,
                            'avg_speed', avg_speed
                        )) as vessel_list,
                        NOW()
                    FROM signal.t_grid_vessel_tracks  
                    WHERE haegu_no = ?
                        AND time_bucket >= ?
                        AND time_bucket < ?
                    GROUP BY haegu_no
                    ON CONFLICT (haegu_no, time_bucket) DO UPDATE SET
                        total_vessels = EXCLUDED.total_vessels,
                        total_distance_nm = EXCLUDED.total_distance_nm,
                        avg_speed = EXCLUDED.avg_speed,
                        vessel_list = EXCLUDED.vessel_list,
                        created_at = NOW()
                """;
                
                jdbcTemplate.update(sql, hourBucket, summary.haeguNo, start, end);
            }
        };
    }
    
    // Area summary reader
    @Bean
    @StepScope
    public JdbcCursorItemReader<String> hourlyAreaReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT area_id
            FROM signal.t_area_vessel_tracks
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY area_id
        """;
        
        return new JdbcCursorItemReaderBuilder<String>()
                .name("hourlyAreaReader")
                .dataSource(queryDataSource)
                .sql(sql)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, start);
                    ps.setObject(2, end);
                })
                .rowMapper((rs, rowNum) -> rs.getString("area_id"))
                .build();
    }
    
    @Bean
    public ItemProcessor<String, HourlyAreaSummary> hourlyAreaProcessor() {
        return new ItemProcessor<String, HourlyAreaSummary>() {
            @Override
            public HourlyAreaSummary process(String areaId) throws Exception {
                HourlyAreaSummary summary = new HourlyAreaSummary();
                summary.areaId = areaId;
                return summary;
            }
        };
    }
    
    @Bean
    @StepScope
    public ItemWriter<HourlyAreaSummary> hourlyAreaWriter(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        return items -> {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            LocalDateTime hourBucket = start.withMinute(0).withSecond(0).withNano(0);
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            for (HourlyAreaSummary summary : items) {
                if (summary == null) continue;
                
                String sql = """
                    INSERT INTO signal.t_area_tracks_summary_hourly
                    (area_id, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list, created_at)
                    SELECT 
                        area_id,
                        ?::timestamp as time_bucket,
                        COUNT(DISTINCT sig_src_cd || '_' || target_id) as total_vessels,
                        SUM(distance_nm) as total_distance_nm,
                        AVG(avg_speed) as avg_speed,
                        jsonb_agg(DISTINCT jsonb_build_object(
                            'sig_src_cd', sig_src_cd,
                            'target_id', target_id,
                            'distance_nm', distance_nm,
                            'avg_speed', avg_speed
                        )) as vessel_list,
                        NOW()
                    FROM signal.t_area_vessel_tracks  
                    WHERE area_id = ?
                        AND time_bucket >= ?
                        AND time_bucket < ?
                    GROUP BY area_id
                    ON CONFLICT (area_id, time_bucket) DO UPDATE SET
                        total_vessels = EXCLUDED.total_vessels,
                        total_distance_nm = EXCLUDED.total_distance_nm,
                        avg_speed = EXCLUDED.avg_speed,
                        vessel_list = EXCLUDED.vessel_list,
                        created_at = NOW()
                """;
                
                jdbcTemplate.update(sql, hourBucket, summary.areaId, start, end);
            }
        };
    }
    
    // 비정상 궤적 검출 관련 빈 정의
    @Bean
    public ItemProcessor<VesselTrack.VesselKey, AbnormalDetectionResult> hourlyTrackProcessorWithAbnormalDetection() {
        return new HourlyTrackProcessorWithAbnormalDetection(
            hourlyTrackItemProcessor(),
            abnormalTrackDetector,
            queryDataSource
        );
    }
    
    @Bean
    public ItemWriter<AbnormalDetectionResult> hourlyCompositeTrackWriter() {
        // Job 이름 직접 설정
        abnormalTrackWriter.setJobName("hourlyAggregationJob");
        return new CompositeTrackWriter(
            vesselTrackBulkWriter,
            abnormalTrackWriter,
            "hourly"
        );
    }
    
    // Summary 클래스들
    public static class HourlyGridSummary {
        public Integer haeguNo;
    }
    
    public static class HourlyAreaSummary {
        public String areaId;
    }
}