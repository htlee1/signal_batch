package gc.mda.signal_batch.batch.job;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import gc.mda.signal_batch.batch.processor.DailyTrackProcessor;
import gc.mda.signal_batch.batch.processor.DailyTrackProcessorWithAbnormalDetection;
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
public class DailyAggregationStepConfig {

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
    public Step mergeDailyTracksStep() {
        // 비정상 궤적 검출은 항상 활성화 (설정 파일로 제어)
        boolean detectAbnormal = true;
        
        if (detectAbnormal) {
            log.info("Building mergeDailyTracksStep with abnormal detection enabled");
            return new StepBuilder("mergeDailyTracksStep", jobRepository)
                    .<VesselTrack.VesselKey, AbnormalDetectionResult>chunk(chunkSize, transactionManager)
                    .reader(dailyVesselKeyReader(null, null))
                    .processor(dailyTrackProcessorWithAbnormalDetection())
                    .writer(dailyCompositeTrackWriter())
                    .build();
        } else {
            log.info("Building mergeDailyTracksStep without abnormal detection");
            return new StepBuilder("mergeDailyTracksStep", jobRepository)
                    .<VesselTrack.VesselKey, VesselTrack>chunk(chunkSize, transactionManager)
                    .reader(dailyVesselKeyReader(null, null))
                    .processor(dailyTrackItemProcessor())
                    .writer(dailyTrackWriter())
                    .build();
        }
    }
    
    @Bean
    public Step gridDailySummaryStep() {
        return new StepBuilder("gridDailySummaryStep", jobRepository)
                .<Integer, DailyGridSummary>chunk(100, transactionManager)
                .reader(dailyGridReader(null, null))
                .processor(dailyGridProcessor())
                .writer(dailyGridWriter(null, null))
                .build();
    }
    
    @Bean
    public Step areaDailySummaryStep() {
        return new StepBuilder("areaDailySummaryStep", jobRepository)
                .<String, DailyAreaSummary>chunk(100, transactionManager)
                .reader(dailyAreaReader(null, null))
                .processor(dailyAreaProcessor())
                .writer(dailyAreaWriter(null, null))
                .build();
    }
    
    @Bean
    @StepScope
    public JdbcCursorItemReader<VesselTrack.VesselKey> dailyVesselKeyReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT sig_src_cd, target_id, date_trunc('day', time_bucket) as day_bucket
            FROM signal.t_vessel_tracks_hourly
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY sig_src_cd, target_id, day_bucket
        """;
        
        return new JdbcCursorItemReaderBuilder<VesselTrack.VesselKey>()
                .name("dailyVesselKeyReader")
                .dataSource(queryDataSource)
                .sql(sql)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, start);
                    ps.setObject(2, end);
                })
                .rowMapper((rs, rowNum) -> new VesselTrack.VesselKey(
                        rs.getString("sig_src_cd"),
                        rs.getString("target_id"),
                        rs.getObject("day_bucket", LocalDateTime.class)
                ))
                .build();
    }
    
    @Bean
    public ItemProcessor<VesselTrack.VesselKey, VesselTrack> dailyTrackItemProcessor() {
        return new DailyTrackProcessor(queryDataSource, new JdbcTemplate(queryDataSource));
    }
    
    @Bean
    public ItemWriter<VesselTrack> dailyTrackWriter() {
        return items -> {
            List<VesselTrack> tracks = new ArrayList<>(items.getItems());
            vesselTrackBulkWriter.writeDailyTracks(tracks);
        };
    }
    
    @Bean
    @StepScope
    public JdbcCursorItemReader<Integer> dailyGridReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT haegu_no
            FROM signal.t_grid_tracks_summary_hourly
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY haegu_no
        """;
        
        return new JdbcCursorItemReaderBuilder<Integer>()
                .name("dailyGridReader")
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
    public ItemProcessor<Integer, DailyGridSummary> dailyGridProcessor() {
        return haeguNo -> {
            DailyGridSummary summary = new DailyGridSummary();
            summary.haeguNo = haeguNo;
            return summary;
        };
    }
    
    @Bean
    @StepScope
    public ItemWriter<DailyGridSummary> dailyGridWriter(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        return items -> {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            LocalDateTime dayBucket = start.withHour(0).withMinute(0).withSecond(0).withNano(0);
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            for (DailyGridSummary summary : items) {
                if (summary == null) continue;
                
                String sql = """
                    INSERT INTO signal.t_grid_tracks_summary_daily
                    (haegu_no, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list, created_at)
                    SELECT 
                        haegu_no,
                        ?::timestamp as time_bucket,
                        COUNT(DISTINCT vessel_key) as total_vessels,
                        SUM(total_distance_nm) as total_distance_nm,
                        AVG(avg_speed) as avg_speed,
                        jsonb_agg(DISTINCT vessel_list) as vessel_list,
                        NOW()
                    FROM (
                        SELECT haegu_no, jsonb_array_elements(vessel_list) as vessel_list,
                               total_distance_nm, avg_speed,
                               (vessel_list->>'sig_src_cd') || '_' || (vessel_list->>'target_id') as vessel_key
                        FROM signal.t_grid_tracks_summary_hourly
                        WHERE haegu_no = ?
                            AND time_bucket >= ?
                            AND time_bucket < ?
                    ) hourly_data
                    GROUP BY haegu_no
                    ON CONFLICT (haegu_no, time_bucket) DO UPDATE SET
                        total_vessels = EXCLUDED.total_vessels,
                        total_distance_nm = EXCLUDED.total_distance_nm,
                        avg_speed = EXCLUDED.avg_speed,
                        vessel_list = EXCLUDED.vessel_list,
                        created_at = NOW()
                """;
                
                jdbcTemplate.update(sql, dayBucket, summary.haeguNo, start, end);
            }
        };
    }
    
    @Bean
    @StepScope
    public JdbcCursorItemReader<String> dailyAreaReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        
        String sql = """
            SELECT DISTINCT area_id
            FROM signal.t_area_tracks_summary_hourly
            WHERE time_bucket >= ? AND time_bucket < ?
            ORDER BY area_id
        """;
        
        return new JdbcCursorItemReaderBuilder<String>()
                .name("dailyAreaReader")
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
    public ItemProcessor<String, DailyAreaSummary> dailyAreaProcessor() {
        return areaId -> {
            DailyAreaSummary summary = new DailyAreaSummary();
            summary.areaId = areaId;
            return summary;
        };
    }
    
    @Bean
    @StepScope
    public ItemWriter<DailyAreaSummary> dailyAreaWriter(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['endTime']}") String endTime) {
        
        return items -> {
            LocalDateTime start = LocalDateTime.parse(startTime);
            LocalDateTime end = LocalDateTime.parse(endTime);
            LocalDateTime dayBucket = start.withHour(0).withMinute(0).withSecond(0).withNano(0);
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            for (DailyAreaSummary summary : items) {
                if (summary == null) continue;
                
                String sql = """
                    INSERT INTO signal.t_area_tracks_summary_daily
                    (area_id, time_bucket, total_vessels, total_distance_nm, avg_speed, vessel_list, created_at)
                    SELECT 
                        area_id,
                        ?::timestamp as time_bucket,
                        COUNT(DISTINCT vessel_key) as total_vessels,
                        SUM(total_distance_nm) as total_distance_nm,
                        AVG(avg_speed) as avg_speed,
                        jsonb_agg(DISTINCT vessel_list) as vessel_list,
                        NOW()
                    FROM (
                        SELECT area_id, jsonb_array_elements(vessel_list) as vessel_list,
                               total_distance_nm, avg_speed,
                               (vessel_list->>'sig_src_cd') || '_' || (vessel_list->>'target_id') as vessel_key
                        FROM signal.t_area_tracks_summary_hourly
                        WHERE area_id = ?
                            AND time_bucket >= ?
                            AND time_bucket < ?
                    ) hourly_data
                    GROUP BY area_id
                    ON CONFLICT (area_id, time_bucket) DO UPDATE SET
                        total_vessels = EXCLUDED.total_vessels,
                        total_distance_nm = EXCLUDED.total_distance_nm,
                        avg_speed = EXCLUDED.avg_speed,
                        vessel_list = EXCLUDED.vessel_list,
                        created_at = NOW()
                """;
                
                jdbcTemplate.update(sql, dayBucket, summary.areaId, start, end);
            }
        };
    }
    
    // 비정상 궤적 검출 관련 빈 정의
    @Bean
    public ItemProcessor<VesselTrack.VesselKey, AbnormalDetectionResult> dailyTrackProcessorWithAbnormalDetection() {
        return new DailyTrackProcessorWithAbnormalDetection(
            dailyTrackItemProcessor(),
            abnormalTrackDetector,
            queryDataSource
        );
    }
    
    @Bean
    public ItemWriter<AbnormalDetectionResult> dailyCompositeTrackWriter() {
        // Job 이름 직접 설정
        abnormalTrackWriter.setJobName("dailyAggregationJob");
        return new CompositeTrackWriter(
            vesselTrackBulkWriter,
            abnormalTrackWriter,
            "daily"
        );
    }
    
    // Summary 클래스들
    public static class DailyGridSummary {
        public Integer haeguNo;
    }
    
    public static class DailyAreaSummary {
        public String areaId;
    }
}