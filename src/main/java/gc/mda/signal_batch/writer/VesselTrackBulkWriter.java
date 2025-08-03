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
            bulkInsertTracks(allTracks);
        } catch (Exception e) {
            log.error("Bulk insert failed, using fallback", e);
            fallbackInsert(allTracks);
        }
    }
    
    private void bulkInsertTracks(List<VesselTrack> tracks) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = """
                COPY signal.t_vessel_tracks_5min (
                    sig_src_cd, target_id, time_bucket, track_geom,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """;
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLine(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} vessel tracks", rowsInserted);
        }
    }
    
    private String formatTrackLine(VesselTrack track) {
        // PostgreSQL COPY 기본 형식: \t로 구분, NULL은 \N
        StringBuilder sb = new StringBuilder();
        sb.append(track.getSigSrcCd()).append("\t")
          .append(track.getTargetId()).append("\t")
          .append(track.getTimeBucket().format(TIMESTAMP_FORMATTER)).append("\t")
          .append(track.getTrackGeom() != null ? 
                  String.format("SRID=4326;%s", track.getTrackGeom()) : "\\N").append("\t")
          .append(track.getDistanceNm() != null ? track.getDistanceNm() : "\\N").append("\t")
          .append(track.getAvgSpeed() != null ? track.getAvgSpeed() : "\\N").append("\t")
          .append(track.getMaxSpeed() != null ? track.getMaxSpeed() : "\\N").append("\t")
          .append(track.getPointCount()).append("\t")
          .append(formatPosition(track.getStartPosition())).append("\t")
          .append(formatPosition(track.getEndPosition()));
        return sb.toString();
    }
    
    private String formatPosition(VesselTrack.TrackPosition position) {
        if (position == null) return "\\N";
        
        try {
            Map<String, Object> posMap = new LinkedHashMap<>(); // 순서 보장
            posMap.put("lat", position.getLat());
            posMap.put("lon", position.getLon());
            posMap.put("sog", position.getSog());
            posMap.put("time", position.getTime().format(TIMESTAMP_FORMATTER));
            
            // ObjectMapper로 JSON 생성
            String json = objectMapper.writeValueAsString(posMap);
            
            // PostgreSQL COPY를 위한 이스케이프
            return json.replace("\\", "\\\\")
                       .replace("\t", "\\t")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r");
        } catch (Exception e) {
            log.error("Failed to convert position to JSON: {}", position, e);
            return "\\N";
        }
    }
    
    private void fallbackInsert(List<VesselTrack> tracks) {
        String sql = """
            INSERT INTO signal.t_vessel_tracks_5min (
                sig_src_cd, target_id, time_bucket, track_geom,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom = EXCLUDED.track_geom,
                distance_nm = EXCLUDED.distance_nm,
                avg_speed = EXCLUDED.avg_speed,
                max_speed = EXCLUDED.max_speed,
                point_count = EXCLUDED.point_count,
                start_position = EXCLUDED.start_position,
                end_position = EXCLUDED.end_position
        """;
        
        List<Object[]> args = tracks.stream()
                .map(track -> new Object[] {
                        track.getSigSrcCd(),
                        track.getTargetId(),
                        Timestamp.valueOf(track.getTimeBucket()),
                        track.getTrackGeom(),
                        track.getDistanceNm(),
                        track.getAvgSpeed(),
                        track.getMaxSpeed(),
                        track.getPointCount(),
                        formatPosition(track.getStartPosition()),
                        formatPosition(track.getEndPosition())
                })
                .collect(Collectors.toList());
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Fallback inserted {} tracks", tracks.size());
    }
    
    // 시간별 트랙 저장
    public void writeHourlyTracks(List<VesselTrack> tracks) throws Exception {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        
        try {
            bulkInsertHourlyTracks(tracks);
        } catch (Exception e) {
            log.error("Hourly bulk insert failed, using fallback", e);
            fallbackInsertHourly(tracks);
        }
    }
    
    private void bulkInsertHourlyTracks(List<VesselTrack> tracks) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = """
                COPY signal.t_vessel_tracks_hourly (
                    sig_src_cd, target_id, time_bucket, track_geom,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """;
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLine(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} hourly vessel tracks", rowsInserted);
        }
    }
    
    private void fallbackInsertHourly(List<VesselTrack> tracks) {
        String sql = """
            INSERT INTO signal.t_vessel_tracks_hourly (
                sig_src_cd, target_id, time_bucket, track_geom,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom = EXCLUDED.track_geom,
                distance_nm = EXCLUDED.distance_nm,
                avg_speed = EXCLUDED.avg_speed,
                max_speed = EXCLUDED.max_speed,
                point_count = EXCLUDED.point_count,
                start_position = EXCLUDED.start_position,
                end_position = EXCLUDED.end_position
        """;
        
        List<Object[]> args = tracks.stream()
                .map(track -> new Object[] {
                        track.getSigSrcCd(),
                        track.getTargetId(),
                        Timestamp.valueOf(track.getTimeBucket()),
                        track.getTrackGeom(),
                        track.getDistanceNm(),
                        track.getAvgSpeed(),
                        track.getMaxSpeed(),
                        track.getPointCount(),
                        formatPosition(track.getStartPosition()),
                        formatPosition(track.getEndPosition())
                })
                .collect(Collectors.toList());
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Fallback inserted {} hourly tracks", tracks.size());
    }
    
    // 일별 트랙 저장
    public void writeDailyTracks(List<VesselTrack> tracks) throws Exception {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        
        try {
            bulkInsertDailyTracks(tracks);
        } catch (Exception e) {
            log.error("Daily bulk insert failed, using fallback", e);
            fallbackInsertDaily(tracks);
        }
    }
    
    private void bulkInsertDailyTracks(List<VesselTrack> tracks) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = """
                COPY signal.t_vessel_tracks_daily (
                    sig_src_cd, target_id, time_bucket, track_geom,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """;
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLine(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} daily vessel tracks", rowsInserted);
        }
    }
    
    private void fallbackInsertDaily(List<VesselTrack> tracks) {
        String sql = """
            INSERT INTO signal.t_vessel_tracks_daily (
                sig_src_cd, target_id, time_bucket, track_geom,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom = EXCLUDED.track_geom,
                distance_nm = EXCLUDED.distance_nm,
                avg_speed = EXCLUDED.avg_speed,
                max_speed = EXCLUDED.max_speed,
                point_count = EXCLUDED.point_count,
                start_position = EXCLUDED.start_position,
                end_position = EXCLUDED.end_position
        """;
        
        List<Object[]> args = tracks.stream()
                .map(track -> new Object[] {
                        track.getSigSrcCd(),
                        track.getTargetId(),
                        Timestamp.valueOf(track.getTimeBucket()),
                        track.getTrackGeom(),
                        track.getDistanceNm(),
                        track.getAvgSpeed(),
                        track.getMaxSpeed(),
                        track.getPointCount(),
                        formatPosition(track.getStartPosition()),
                        formatPosition(track.getEndPosition())
                })
                .collect(Collectors.toList());
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Fallback inserted {} daily tracks", tracks.size());
    }
}
