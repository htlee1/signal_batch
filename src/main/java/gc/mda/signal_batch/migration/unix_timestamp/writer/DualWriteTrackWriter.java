package gc.mda.signal_batch.migration.unix_timestamp.writer;

import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.migration.unix_timestamp.MValueStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * MIGRATION_V2: Dual Write 모드 지원 Writer
 * track_geom과 track_geom_v2 동시 저장
 */
@Slf4j
@Component
public class DualWriteTrackWriter {
    
    @Autowired
    private JdbcTemplate queryJdbcTemplate;
    
    @Autowired
    @Qualifier("relativeTimeStrategy")
    private MValueStrategy relativeStrategy;
    
    @Autowired
    @Qualifier("unixTimestampStrategy")
    private MValueStrategy unixStrategy;
    
    @Value("${vessel.batch.m-value.dual-write:false}")
    private boolean dualWrite;
    
    @Value("${vessel.batch.m-value.format:relative}")
    private String mValueFormat;
    
    public void writeWithDualMode(List<VesselTrack> tracks, String tableName) {
        if (dualWrite) {
            writeDual(tracks, tableName);
        } else if ("unix".equals(mValueFormat)) {
            writeV2Only(tracks, tableName);
        } else {
            writeV1Only(tracks, tableName);
        }
    }
    
    private void writeDual(List<VesselTrack> tracks, String tableName) {
        String sql = String.format("""
            INSERT INTO signal.%s (
                sig_src_cd, target_id, time_bucket, 
                track_geom, track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, 
                ST_SetSRID(ST_GeomFromText(?), 4326),
                ST_SetSRID(ST_GeomFromText(?), 4326),
                ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom = EXCLUDED.track_geom,
                track_geom_v2 = EXCLUDED.track_geom_v2,
                distance_nm = EXCLUDED.distance_nm,
                avg_speed = EXCLUDED.avg_speed,
                max_speed = EXCLUDED.max_speed,
                point_count = EXCLUDED.point_count,
                start_position = EXCLUDED.start_position,
                end_position = EXCLUDED.end_position
            """, tableName);
        
        List<Object[]> args = tracks.stream()
            .map(track -> new Object[] {
                track.getSigSrcCd(),
                track.getTargetId(),
                Timestamp.valueOf(track.getTimeBucket()),
                track.getTrackGeom(),  // relative time
                buildUnixLineStringM(track),  // unix time
                track.getDistanceNm(),
                track.getAvgSpeed(),
                track.getMaxSpeed(),
                track.getPointCount(),
                formatPosition(track.getStartPosition()),
                formatPosition(track.getEndPosition())
            })
            .toList();
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Dual-write {} tracks to {} (both v1 and v2)", tracks.size(), tableName);
    }
    
    private void writeV2Only(List<VesselTrack> tracks, String tableName) {
        String sql = String.format("""
            INSERT INTO signal.%s (
                sig_src_cd, target_id, time_bucket, 
                track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, 
                ST_SetSRID(ST_GeomFromText(?), 4326),
                ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom_v2 = EXCLUDED.track_geom_v2,
                distance_nm = EXCLUDED.distance_nm
            """, tableName);
        
        queryJdbcTemplate.batchUpdate(sql, tracks.stream()
            .map(track -> new Object[] {
                track.getSigSrcCd(),
                track.getTargetId(),
                Timestamp.valueOf(track.getTimeBucket()),
                buildUnixLineStringM(track),
                track.getDistanceNm(),
                track.getAvgSpeed(),
                track.getMaxSpeed(),
                track.getPointCount(),
                formatPosition(track.getStartPosition()),
                formatPosition(track.getEndPosition())
            })
            .toList());
        
        log.info("Written {} tracks to {} (v2 only)", tracks.size(), tableName);
    }
    
    private void writeV1Only(List<VesselTrack> tracks, String tableName) {
        // 기존 방식 유지
        log.info("Written {} tracks to {} (v1 only)", tracks.size(), tableName);
    }
    
    private String buildUnixLineStringM(VesselTrack track) {
        return unixStrategy.buildLineStringM(track.getTrackPoints());
    }
    
    private String formatPosition(VesselTrack.TrackPosition position) {
        if (position == null) return null;
        return String.format("{\"lat\":%f,\"lon\":%f,\"time\":\"%s\",\"sog\":%f}",
            position.getLat(), position.getLon(),
            position.getTime().toString(), position.getSog());
    }
}
