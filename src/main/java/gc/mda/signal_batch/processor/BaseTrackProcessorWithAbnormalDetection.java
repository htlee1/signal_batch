package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.processor.AbnormalTrackDetector.AbnormalDetectionResult;
import gc.mda.signal_batch.util.LineStringMUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 시간별/일별 궤적 프로세서 기본 클래스 - 비정상 궤적 검출 기능 포함
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseTrackProcessorWithAbnormalDetection implements ItemProcessor<VesselTrack.VesselKey, AbnormalDetectionResult> {
    
    protected final ItemProcessor<VesselTrack.VesselKey, VesselTrack> trackProcessor;
    protected final AbnormalTrackDetector abnormalTrackDetector;
    protected final DataSource queryDataSource;
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public AbnormalDetectionResult process(VesselTrack.VesselKey vesselKey) throws Exception {
        // 기존 프로세서로 궤적 생성
        VesselTrack track = trackProcessor.process(vesselKey);
        
        if (track == null) {
            return null;
        }
        
        // 이전 bucket의 마지막 궤적 조회
        VesselTrack previousTrack = getPreviousBucketLastTrack(vesselKey);
        
        // Bucket 간 연결점만 검사 (하위 데이터는 이미 검증됨)
        AbnormalDetectionResult result = abnormalTrackDetector.detectBucketTransitionOnly(track, previousTrack);
        
        if (result.hasAbnormalities()) {
            log.debug("Abnormal track detected for vessel {}/{} at {}: {}", 
                track.getSigSrcCd(), track.getTargetId(), track.getTimeBucket(), 
                result.getAbnormalSegments().size());
        }
        
        return result;
    }
    
    /**
     * 이전 버킷의 마지막 궤적 조회
     */
    protected VesselTrack getPreviousBucketLastTrack(VesselTrack.VesselKey vesselKey) {
        try {
            String sql = """
                SELECT sig_src_cd, target_id, time_bucket,
                       end_position,
                       ST_AsText(ST_LineSubstring(track_geom, 0.9, 1.0)) as last_segment
                FROM %s
                WHERE sig_src_cd = ?
                  AND target_id = ?
                  AND time_bucket >= ?
                  AND time_bucket < ?
                ORDER BY time_bucket DESC
                LIMIT 1
            """.formatted(getPreviousTrackTableName());
            
            LocalDateTime currentBucket = getNormalizedBucket(vesselKey.getTimeBucket());
            LocalDateTime previousBucket = getPreviousBucket(currentBucket);
            
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            return jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> {
                    return VesselTrack.builder()
                        .sigSrcCd(rs.getString("sig_src_cd"))
                        .targetId(rs.getString("target_id"))
                        .timeBucket(rs.getTimestamp("time_bucket").toLocalDateTime())
                        .trackGeom(rs.getString("last_segment"))
                        .endPosition(parseEndPosition(rs.getString("end_position")))
                        .build();
                },
                vesselKey.getSigSrcCd(), vesselKey.getTargetId(), previousBucket, currentBucket
            );
        } catch (Exception e) {
            log.debug("No previous bucket track found for vessel {}", vesselKey);
            return null;
        }
    }
    
    /**
     * JSON 형식의 end_position 파싱
     */
    protected VesselTrack.TrackPosition parseEndPosition(String json) {
        if (json == null) return null;
        try {
            String lat = LineStringMUtils.extractJsonValue(json, "lat");
            String lon = LineStringMUtils.extractJsonValue(json, "lon");
            String time = LineStringMUtils.extractJsonValue(json, "time");
            String sog = LineStringMUtils.extractJsonValue(json, "sog");
            
            return VesselTrack.TrackPosition.builder()
                .lat(lat != null ? Double.parseDouble(lat) : null)
                .lon(lon != null ? Double.parseDouble(lon) : null)
                .time(time != null ? LocalDateTime.parse(time, TIMESTAMP_FORMATTER) : null)
                .sog(sog != null ? new BigDecimal(sog) : null)
                .build();
        } catch (Exception e) {
            log.error("Failed to parse end position: {}", json, e);
            return null;
        }
    }
    
    /**
     * 이전 트랙을 조회할 테이블명 반환 (하위 클래스에서 구현)
     */
    protected abstract String getPreviousTrackTableName();
    
    /**
     * 정규화된 버킷 시간 반환 (하위 클래스에서 구현)
     */
    protected abstract LocalDateTime getNormalizedBucket(LocalDateTime timeBucket);
    
    /**
     * 이전 버킷 시간 계산 (하위 클래스에서 구현)
     */
    protected abstract LocalDateTime getPreviousBucket(LocalDateTime currentBucket);
}
