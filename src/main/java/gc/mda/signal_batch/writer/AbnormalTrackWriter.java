package gc.mda.signal_batch.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.processor.AbnormalTrackDetector.AbnormalDetectionResult;
import gc.mda.signal_batch.processor.AbnormalTrackDetector.AbnormalSegment;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 비정상 궤적 데이터를 별도 테이블에 저장하는 Writer
 */
@Slf4j
@Component
public class AbnormalTrackWriter implements ItemWriter<AbnormalDetectionResult> {
    
    @Autowired
    @Qualifier("queryJdbcTemplate")
    private JdbcTemplate jdbcTemplate;
    
    @Value("${vessel.batch.m-value.format:relative}")
    private String mValueFormat;
    
    @Value("${vessel.batch.m-value.dual-write:false}")
    private boolean dualWrite;
    
    @Value("${vessel.batch.m-value.read-column:track_geom}")
    private String readColumn;
    
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    private String jobName;
    
    public void setJobName(String jobName) {
        this.jobName = jobName;
        log.debug("AbnormalTrackWriter: Job name set to {}", jobName);
    }
    
    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        // CompositeTrackWriter에서 이미 설정된 경우 사용
        if (this.jobName == null) {
            // 직접 호출된 경우만 Job 이름 가져오기
            this.jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
            log.debug("Job name from StepExecution: {}", jobName);
        }
    }
    
    @Override
    @Transactional
    public void write(Chunk<? extends AbnormalDetectionResult> items) throws Exception {
        List<AbnormalDetectionResult> abnormalResults = items.getItems().stream()
                .filter(AbnormalDetectionResult::hasAbnormalities)
                .collect(Collectors.toList());
        
        if (abnormalResults.isEmpty()) {
            return;
        }
        
        // 1. 비정상 궤적 저장
        saveAbnormalTracks(abnormalResults);
        
        // 2. 통계 업데이트
        updateAbnormalStats(abnormalResults);
        
        log.info("비정상 궤적 저장 완료: {} 건", abnormalResults.size());
    }
    
    private void saveAbnormalTracks(List<AbnormalDetectionResult> results) {
        // 설정에 따른 컬럼 선택
        boolean useV2 = "track_geom_v2".equals(readColumn) || "unix".equals(mValueFormat);
        String geomColumn = useV2 ? "track_geom_v2" : "track_geom";
        
        String sql = String.format("""
            INSERT INTO signal.t_abnormal_tracks (
                sig_src_cd, target_id, time_bucket, %s,
                abnormal_type, abnormal_reason, distance_nm, avg_speed,
                max_speed, point_count, source_table
            ) VALUES (?, ?, ?, ST_GeomFromText(?, 4326), ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (sig_src_cd, target_id, time_bucket, source_table) 
            DO UPDATE SET
                %s = EXCLUDED.%s,
                abnormal_type = EXCLUDED.abnormal_type,
                abnormal_reason = EXCLUDED.abnormal_reason,
                distance_nm = EXCLUDED.distance_nm,
                avg_speed = EXCLUDED.avg_speed,
                max_speed = EXCLUDED.max_speed,
                point_count = EXCLUDED.point_count,
                detected_at = NOW()
        """, geomColumn, geomColumn, geomColumn);
        
        List<Object[]> batchArgs = new ArrayList<>();
        
        for (AbnormalDetectionResult result : results) {
            VesselTrack track = result.getOriginalTrack();
            List<AbnormalSegment> segments = result.getAbnormalSegments();
            
            // 주요 비정상 유형 결정 (첫 번째 검출된 유형)
            String mainAbnormalType = segments.get(0).getType();
            
            // 비정상 이유 JSON 생성
            Map<String, Object> abnormalReason = new HashMap<>();
            abnormalReason.put("segments", segments.stream()
                .map(seg -> {
                    Map<String, Object> segMap = new HashMap<>();
                    segMap.put("type", seg.getType());
                    segMap.put("description", seg.getDescription());
                    segMap.put("actualValue", seg.getActualValue());
                    segMap.put("threshold", seg.getThreshold());
                    segMap.put("details", seg.getDetails());
                    return segMap;
                })
                .collect(Collectors.toList()));
            
            try {
                String reasonJson = objectMapper.writeValueAsString(abnormalReason);
                // VesselTrack에서 적절한 geometry 선택
                String geomWkt = null;
                if (useV2) {
                    // track_geom_v2 우선 사용
                    geomWkt = track.getTrackGeomV2() != null ? track.getTrackGeomV2() : track.getTrackGeom();
                } else {
                    // track_geom 우선 사용
                    geomWkt = track.getTrackGeom() != null ? track.getTrackGeom() : track.getTrackGeomV2();
                }
                
                if (geomWkt == null) {
                    log.warn("비정상 궤적에 geometry 데이터 없음: vessel={}", track.getVesselKey());
                    continue;
                }
                
                batchArgs.add(new Object[] {
                    track.getSigSrcCd(),
                    track.getTargetId(),
                    Timestamp.valueOf(track.getTimeBucket()),
                    geomWkt,
                    mainAbnormalType,
                    reasonJson,
                    track.getDistanceNm(),
                    track.getAvgSpeed(),
                    track.getMaxSpeed(),
                    track.getPointCount(),
                    inferSourceTableFromJob()
                });
            } catch (Exception e) {
                log.error("비정상 궤적 JSON 변환 실패: vessel={}, error={}", 
                    track.getVesselKey(), e.getMessage());
            }
        }
        
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
            log.info("비정상 궤적 DB 저장: {} 건", batchArgs.size());
        }
    }
    
    private void updateAbnormalStats(List<AbnormalDetectionResult> results) {
        LocalDate today = LocalDate.now();
        
        // 비정상 유형별 통계 집계
        Map<String, Stats> typeStats = new HashMap<>();
        
        for (AbnormalDetectionResult result : results) {
            for (AbnormalSegment segment : result.getAbnormalSegments()) {
                typeStats.computeIfAbsent(segment.getType(), k -> new Stats())
                    .add(result.getOriginalTrack(), segment);
            }
        }
        
        // DB 업데이트
        String sql = """
            INSERT INTO signal.t_abnormal_track_stats (
                stat_date, abnormal_type, vessel_count, track_count,
                total_points, avg_deviation, max_deviation
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (stat_date, abnormal_type)
            DO UPDATE SET
                vessel_count = t_abnormal_track_stats.vessel_count + EXCLUDED.vessel_count,
                track_count = t_abnormal_track_stats.track_count + EXCLUDED.track_count,
                total_points = t_abnormal_track_stats.total_points + EXCLUDED.total_points,
                avg_deviation = (t_abnormal_track_stats.avg_deviation * t_abnormal_track_stats.track_count + 
                                EXCLUDED.avg_deviation * EXCLUDED.track_count) / 
                                (t_abnormal_track_stats.track_count + EXCLUDED.track_count),
                max_deviation = GREATEST(t_abnormal_track_stats.max_deviation, EXCLUDED.max_deviation),
                updated_at = NOW()
        """;
        
        for (Map.Entry<String, Stats> entry : typeStats.entrySet()) {
            Stats stats = entry.getValue();
            jdbcTemplate.update(sql,
                today,
                entry.getKey(),
                stats.vesselIds.size(),
                stats.trackCount,
                stats.totalPoints,
                stats.getAvgDeviation(),
                stats.maxDeviation
            );
        }
        
        log.info("비정상 궤적 통계 업데이트: {} 개 유형", typeStats.size());
    }
    
    /**
     * Job 이름을 기반으로 source_table 결정
     */
    private String inferSourceTableFromJob() {
        if (jobName == null) {
            log.warn("Job name is null, defaulting to 5min table");
            return "t_vessel_tracks_5min";
        }
        
        // Job 이름 기반 판단
        if (jobName.toLowerCase().contains("daily")) {
            return "t_vessel_tracks_daily";
        } else if (jobName.toLowerCase().contains("hourly")) {
            return "t_vessel_tracks_hourly";
        } else {
            return "t_vessel_tracks_5min";
        }
    }

    
    private static class Stats {
        private final List<String> vesselIds = new ArrayList<>();
        private int trackCount = 0;
        private int totalPoints = 0;
        private double sumDeviation = 0;
        private double maxDeviation = 0;
        
        void add(VesselTrack track, AbnormalSegment segment) {
            vesselIds.add(track.getVesselKey());
            trackCount++;
            totalPoints += track.getPointCount();
            
            double deviation = segment.getActualValue() - segment.getThreshold();
            sumDeviation += deviation;
            maxDeviation = Math.max(maxDeviation, deviation);
        }
        
        double getAvgDeviation() {
            return trackCount > 0 ? sumDeviation / trackCount : 0;
        }
    }
}
