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
import org.springframework.beans.factory.annotation.Value;
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
    
    @Value("${vessel.batch.m-value.format:relative}")
    private String mValueFormat;
    
    @Value("${vessel.batch.m-value.dual-write:false}")
    private boolean dualWrite;
    
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
            if (dualWrite) {
                bulkInsertTracksDualMode(allTracks);
            } else if ("unix".equals(mValueFormat)) {
                bulkInsertTracksV2Only(allTracks);
            } else {
                bulkInsertTracks(allTracks);
            }
        } catch (Exception e) {
            log.error("Bulk insert failed, using fallback", e);
            if (dualWrite) {
                fallbackInsertDual(allTracks);
            } else if ("unix".equals(mValueFormat)) {
                fallbackInsertV2(allTracks);
            } else {
                fallbackInsert(allTracks);
            }
        }
    }
    
    // MIGRATION_V2: Dual-write 모드 COPY
    private void bulkInsertTracksDualMode(List<VesselTrack> tracks) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = """
                COPY signal.t_vessel_tracks_5min (
                    sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """;
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLineDual(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Dual-mode bulk inserted {} vessel tracks (both v1 and v2)", rowsInserted);
        }
    }
    
    // MIGRATION_V2: Unix timestamp 전용 COPY
    private void bulkInsertTracksV2Only(List<VesselTrack> tracks) throws Exception {
        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);
            
            String copySql = """
                COPY signal.t_vessel_tracks_5min (
                    sig_src_cd, target_id, time_bucket, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) FROM STDIN
            """;
            
            StringWriter writer = new StringWriter();
            for (VesselTrack track : tracks) {
                writer.write(formatTrackLineV2(track));
                writer.write('\n');
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("V2-only bulk inserted {} vessel tracks", rowsInserted);
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
    
    // MIGRATION_V2: Dual 모드 라인 포맷
    private String formatTrackLineDual(VesselTrack track) {
        StringBuilder sb = new StringBuilder();
        sb.append(track.getSigSrcCd()).append("\t")
          .append(track.getTargetId()).append("\t")
          .append(track.getTimeBucket().format(TIMESTAMP_FORMATTER)).append("\t")
          .append(track.getTrackGeom() != null ? 
                  String.format("SRID=4326;%s", track.getTrackGeom()) : "\\N").append("\t")
          .append(track.getTrackGeomV2() != null ? 
                  String.format("SRID=4326;%s", track.getTrackGeomV2()) : "\\N").append("\t")
          .append(track.getDistanceNm() != null ? track.getDistanceNm() : "\\N").append("\t")
          .append(track.getAvgSpeed() != null ? track.getAvgSpeed() : "\\N").append("\t")
          .append(track.getMaxSpeed() != null ? track.getMaxSpeed() : "\\N").append("\t")
          .append(track.getPointCount()).append("\t")
          .append(formatPosition(track.getStartPosition())).append("\t")
          .append(formatPosition(track.getEndPosition()));
        return sb.toString();
    }
    
    // MIGRATION_V2: V2 전용 라인 포맷
    private String formatTrackLineV2(VesselTrack track) {
        StringBuilder sb = new StringBuilder();
        sb.append(track.getSigSrcCd()).append("\t")
          .append(track.getTargetId()).append("\t")
          .append(track.getTimeBucket().format(TIMESTAMP_FORMATTER)).append("\t")
          .append(track.getTrackGeomV2() != null ? 
                  String.format("SRID=4326;%s", track.getTrackGeomV2()) : "\\N").append("\t")
          .append(track.getDistanceNm() != null ? track.getDistanceNm() : "\\N").append("\t")
          .append(track.getAvgSpeed() != null ? track.getAvgSpeed() : "\\N").append("\t")
          .append(track.getMaxSpeed() != null ? track.getMaxSpeed() : "\\N").append("\t")
          .append(track.getPointCount()).append("\t")
          .append(formatPosition(track.getStartPosition())).append("\t")
          .append(formatPosition(track.getEndPosition()));
        return sb.toString();
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
    
    // MIGRATION_V2: Dual-write fallback
    private void fallbackInsertDual(List<VesselTrack> tracks) {
        String sql = """
            INSERT INTO signal.t_vessel_tracks_5min (
                sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom = EXCLUDED.track_geom,
                track_geom_v2 = EXCLUDED.track_geom_v2,
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
                        track.getTrackGeomV2(),
                        track.getDistanceNm(),
                        track.getAvgSpeed(),
                        track.getMaxSpeed(),
                        track.getPointCount(),
                        formatPosition(track.getStartPosition()),
                        formatPosition(track.getEndPosition())
                })
                .collect(Collectors.toList());
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Dual-mode fallback inserted {} tracks", tracks.size());
    }
    
    // MIGRATION_V2: V2 전용 fallback
    private void fallbackInsertV2(List<VesselTrack> tracks) {
        String sql = """
            INSERT INTO signal.t_vessel_tracks_5min (
                sig_src_cd, target_id, time_bucket, track_geom_v2,
                distance_nm, avg_speed, max_speed, point_count,
                start_position, end_position
            ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                track_geom_v2 = EXCLUDED.track_geom_v2,
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
                        track.getTrackGeomV2(),
                        track.getDistanceNm(),
                        track.getAvgSpeed(),
                        track.getMaxSpeed(),
                        track.getPointCount(),
                        formatPosition(track.getStartPosition()),
                        formatPosition(track.getEndPosition())
                })
                .collect(Collectors.toList());
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("V2-only fallback inserted {} tracks", tracks.size());
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
            
            String copySql;
            StringWriter writer = new StringWriter();
            
            if (dualWrite) {
                copySql = """
                    COPY signal.t_vessel_tracks_hourly (
                        sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLineDual(track));
                    writer.write('\n');
                }
            } else if ("unix".equals(mValueFormat)) {
                copySql = """
                    COPY signal.t_vessel_tracks_hourly (
                        sig_src_cd, target_id, time_bucket, track_geom_v2,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLineV2(track));
                    writer.write('\n');
                }
            } else {
                copySql = """
                    COPY signal.t_vessel_tracks_hourly (
                        sig_src_cd, target_id, time_bucket, track_geom,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLine(track));
                    writer.write('\n');
                }
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} hourly vessel tracks (mode: {})", 
                    rowsInserted, dualWrite ? "dual" : mValueFormat);
        }
    }
    
    private void fallbackInsertHourly(List<VesselTrack> tracks) {
        String sql;
        List<Object[]> args;
        
        if (dualWrite) {
            sql = """
                INSERT INTO signal.t_vessel_tracks_hourly (
                    sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                    track_geom = EXCLUDED.track_geom,
                    track_geom_v2 = EXCLUDED.track_geom_v2,
                    distance_nm = EXCLUDED.distance_nm,
                    avg_speed = EXCLUDED.avg_speed,
                    max_speed = EXCLUDED.max_speed,
                    point_count = EXCLUDED.point_count,
                    start_position = EXCLUDED.start_position,
                    end_position = EXCLUDED.end_position
            """;
            
            args = tracks.stream()
                    .map(track -> new Object[] {
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getTrackGeom(),
                            track.getTrackGeomV2(),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getMaxSpeed(),
                            track.getPointCount(),
                            formatPosition(track.getStartPosition()),
                            formatPosition(track.getEndPosition())
                    })
                    .collect(Collectors.toList());
        } else if ("unix".equals(mValueFormat)) {
            sql = """
                INSERT INTO signal.t_vessel_tracks_hourly (
                    sig_src_cd, target_id, time_bucket, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                    track_geom_v2 = EXCLUDED.track_geom_v2,
                    distance_nm = EXCLUDED.distance_nm,
                    avg_speed = EXCLUDED.avg_speed,
                    max_speed = EXCLUDED.max_speed,
                    point_count = EXCLUDED.point_count,
                    start_position = EXCLUDED.start_position,
                    end_position = EXCLUDED.end_position
            """;
            
            args = tracks.stream()
                    .map(track -> new Object[] {
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getTrackGeomV2(),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getMaxSpeed(),
                            track.getPointCount(),
                            formatPosition(track.getStartPosition()),
                            formatPosition(track.getEndPosition())
                    })
                    .collect(Collectors.toList());
        } else {
            sql = """
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
            
            args = tracks.stream()
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
        }
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Fallback inserted {} hourly tracks (mode: {})", 
                tracks.size(), dualWrite ? "dual" : mValueFormat);
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
            
            String copySql;
            StringWriter writer = new StringWriter();
            
            if (dualWrite) {
                copySql = """
                    COPY signal.t_vessel_tracks_daily (
                        sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLineDual(track));
                    writer.write('\n');
                }
            } else if ("unix".equals(mValueFormat)) {
                copySql = """
                    COPY signal.t_vessel_tracks_daily (
                        sig_src_cd, target_id, time_bucket, track_geom_v2,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLineV2(track));
                    writer.write('\n');
                }
            } else {
                copySql = """
                    COPY signal.t_vessel_tracks_daily (
                        sig_src_cd, target_id, time_bucket, track_geom,
                        distance_nm, avg_speed, max_speed, point_count,
                        start_position, end_position
                    ) FROM STDIN
                """;
                for (VesselTrack track : tracks) {
                    writer.write(formatTrackLine(track));
                    writer.write('\n');
                }
            }
            
            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.info("Bulk inserted {} daily vessel tracks (mode: {})",
                    rowsInserted, dualWrite ? "dual" : mValueFormat);
        }
    }
    
    private void fallbackInsertDaily(List<VesselTrack> tracks) {
        String sql;
        List<Object[]> args;
        
        if (dualWrite) {
            sql = """
                INSERT INTO signal.t_vessel_tracks_daily (
                    sig_src_cd, target_id, time_bucket, track_geom, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                    track_geom = EXCLUDED.track_geom,
                    track_geom_v2 = EXCLUDED.track_geom_v2,
                    distance_nm = EXCLUDED.distance_nm,
                    avg_speed = EXCLUDED.avg_speed,
                    max_speed = EXCLUDED.max_speed,
                    point_count = EXCLUDED.point_count,
                    start_position = EXCLUDED.start_position,
                    end_position = EXCLUDED.end_position
            """;
            
            args = tracks.stream()
                    .map(track -> new Object[] {
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getTrackGeom(),
                            track.getTrackGeomV2(),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getMaxSpeed(),
                            track.getPointCount(),
                            formatPosition(track.getStartPosition()),
                            formatPosition(track.getEndPosition())
                    })
                    .collect(Collectors.toList());
        } else if ("unix".equals(mValueFormat)) {
            sql = """
                INSERT INTO signal.t_vessel_tracks_daily (
                    sig_src_cd, target_id, time_bucket, track_geom_v2,
                    distance_nm, avg_speed, max_speed, point_count,
                    start_position, end_position
                ) VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326), ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sig_src_cd, target_id, time_bucket) DO UPDATE SET
                    track_geom_v2 = EXCLUDED.track_geom_v2,
                    distance_nm = EXCLUDED.distance_nm,
                    avg_speed = EXCLUDED.avg_speed,
                    max_speed = EXCLUDED.max_speed,
                    point_count = EXCLUDED.point_count,
                    start_position = EXCLUDED.start_position,
                    end_position = EXCLUDED.end_position
            """;
            
            args = tracks.stream()
                    .map(track -> new Object[] {
                            track.getSigSrcCd(),
                            track.getTargetId(),
                            Timestamp.valueOf(track.getTimeBucket()),
                            track.getTrackGeomV2(),
                            track.getDistanceNm(),
                            track.getAvgSpeed(),
                            track.getMaxSpeed(),
                            track.getPointCount(),
                            formatPosition(track.getStartPosition()),
                            formatPosition(track.getEndPosition())
                    })
                    .collect(Collectors.toList());
        } else {
            sql = """
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
            
            args = tracks.stream()
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
        }
        
        queryJdbcTemplate.batchUpdate(sql, args);
        log.info("Fallback inserted {} daily tracks (mode: {})", 
                tracks.size(), dualWrite ? "dual" : mValueFormat);
    }
}
