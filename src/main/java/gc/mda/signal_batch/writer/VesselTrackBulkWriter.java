package gc.mda.signal_batch.writer;

import gc.mda.signal_batch.model.VesselTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.sql.DataSource;
import java.io.*;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VesselTrackBulkWriter implements ItemWriter<List<VesselTrack>> {
    
    private final DataSource queryDataSource;
    private final JdbcTemplate queryJdbcTemplate;
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setDateFormat(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    
    @Override
    public void write(Chunk<? extends List<VesselTrack>> chunk) throws Exception {
        List<VesselTrack> allTracks = chunk.getItems().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        
        if (allTracks.isEmpty()) {
            return;
        }
        
        try {
            bulkInsertTracks(allTracks, "signal.t_vessel_tracks_5min");
        } catch (Exception e) {
            log.error("Bulk insert failed, using fallback", e);
            fallbackInsert(allTracks, "signal.t_vessel_tracks_5min");
        }
    }
    
    public void writeHourlyTracks(List<VesselTrack> tracks) throws Exception {
        if (tracks.isEmpty()) {
            return;
        }
        
        try {
            bulkInsertTracks(tracks, "signal.t_vessel_tracks_hourly");
        } catch (Exception e) {
            log.error("Hourly bulk insert failed, using fallback", e);
            fallbackInsert(tracks, "signal.t_vessel_tracks_hourly");
        }
    }
    
    public void writeDailyTracks(List<VesselTrack> tracks) throws Exception {
        if (tracks.isEmpty()) {
            return;
        }
        
        try {
            bulkInsertTracks(tracks, "signal.t_vessel_tracks_daily");
        } catch (Exception e) {
            log.error("Daily bulk insert failed, using fallback", e);
            fallbackInsert(tracks, "signal.t_vessel_tracks_daily");
        }
    }
    
    // track_geom만 사용하는 단순화된 COPY
    private void bulkInsertTracks(List<VesselTrack> tracks, String tableName) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = String.format("""
                COPY %s (
                    sig_src_cd, target_id, time_bucket, track_geom,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """, tableName);
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLine(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} vessel tracks to {} (v2 only)", rowsInserted, tableName);
        }
    }
    
    private String formatTrackLine(VesselTrack track) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(track.getSigSrcCd()).append('\t');
        sb.append(track.getTargetId()).append('\t');
        sb.append(Timestamp.valueOf(track.getTimeBucket())).append('\t');
        
        // track_geom만 사용
        if (track.getTrackGeom() != null && !track.getTrackGeom().isEmpty()) {
            sb.append(track.getTrackGeom());
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        
        // distance_nm
        if (track.getDistanceNm() != null) {
            sb.append(track.getDistanceNm());
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        
        // avg_speed
        if (track.getAvgSpeed() != null) {
            sb.append(track.getAvgSpeed());
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        
        // max_speed
        if (track.getMaxSpeed() != null) {
            sb.append(track.getMaxSpeed());
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        
        // point_count
        sb.append(track.getPointCount()).append('\t');
        
        // start_position (JSON)
        if (track.getStartPosition() != null) {
            sb.append(formatPositionJson(track.getStartPosition()));
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        
        // end_position (JSON)
        if (track.getEndPosition() != null) {
            sb.append(formatPositionJson(track.getEndPosition()));
        } else {
            sb.append("\\N");
        }
        
        return sb.toString();
    }
    
    private String formatPositionJson(VesselTrack.TrackPosition position) {
        Map<String, Object> jsonMap = new LinkedHashMap<>();
        jsonMap.put("lat", position.getLat());
        jsonMap.put("lon", position.getLon());
        jsonMap.put("time", position.getTime().format(TIMESTAMP_FORMATTER));
        if (position.getSog() != null) {
            jsonMap.put("sog", position.getSog());
        }
        
        try {
            return objectMapper.writeValueAsString(jsonMap);
        } catch (Exception e) {
            log.error("Failed to format position JSON", e);
            return "{}";
        }
    }
    
    private void fallbackInsert(List<VesselTrack> tracks, String tableName) {
        String sql = String.format("""
            INSERT INTO %s (
                sig_src_cd, target_id, time_bucket, track_geom,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_GeomFromText(?), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
        """, tableName);
        
        for (VesselTrack track : tracks) {
            try {
                queryJdbcTemplate.update(sql,
                    track.getSigSrcCd(),
                    track.getTargetId(),
                    Timestamp.valueOf(track.getTimeBucket()),
                    track.getTrackGeom(),
                    track.getDistanceNm(),
                    track.getAvgSpeed(),
                    track.getMaxSpeed(),
                    track.getPointCount(),
                    track.getStartPosition() != null ? formatPositionJson(track.getStartPosition()) : null,
                    track.getEndPosition() != null ? formatPositionJson(track.getEndPosition()) : null
                );
            } catch (Exception e) {
                log.error("Failed to insert track for vessel: {} to {}", 
                    track.getSigSrcCd() + "_" + track.getTargetId(), tableName, e);
            }
        }
    }
}
