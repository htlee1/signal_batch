package gc.mda.signal_batch.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.annotation.BeforeJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VesselTrackDataJobListener implements JobExecutionListener {
    
    private final JdbcTemplate collectJdbcTemplate;
    private final VesselTrackDataHolder vesselTrackDataHolder;
    private final AreaBoundaryCache areaBoundaryCache;  // 추가
    
    @Value("${vessel.batch.fetch-size:50000}")
    private int fetchSize;
    
    @BeforeJob
    public void beforeJob(JobExecution jobExecution) {
        // Area/Haegu 경계 캐시 갱신
        areaBoundaryCache.refresh();
        log.info("Refreshed area boundary cache");
        
        LocalDateTime startTime = LocalDateTime.parse(
                jobExecution.getJobParameters().getString("startTime"));
        LocalDateTime endTime = LocalDateTime.parse(
                jobExecution.getJobParameters().getString("endTime"));
        
        log.info("Loading all vessel data for track generation from {} to {}", startTime, endTime);
        
        // 5분간 전체 데이터 조회 (최신 위치가 아닌 모든 포인트)
        String sql = """
            SELECT message_time, real_time, sig_src_cd, target_id, 
                   lat, lon, sog, cog, heading, ship_nm, ship_ty, 
                   rot, posacc, sensor_id, base_st_id, mode, 
                   gps_sttus, battery_sttus, vts_cd, mmsi, vpass_id, ship_no
            FROM signal.sig_test 
            WHERE message_time >= ? AND message_time < ?
              AND lat IS NOT NULL AND lon IS NOT NULL
              AND lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180
              AND sig_src_cd != '000005'
              AND length(target_id) > 5
            ORDER BY sig_src_cd, target_id, message_time
        """;
        
        collectJdbcTemplate.setFetchSize(fetchSize);
        
        List<gc.mda.signal_batch.model.VesselData> vesselDataList = collectJdbcTemplate.query(
                sql,
                new Object[]{Timestamp.valueOf(startTime), Timestamp.valueOf(endTime)},
                (rs, rowNum) -> {
                    gc.mda.signal_batch.model.VesselData data = new gc.mda.signal_batch.model.VesselData();
                    
                    Timestamp messageTime = rs.getTimestamp("message_time");
                    if (messageTime != null) {
                        data.setMessageTime(messageTime.toLocalDateTime());
                    }
                    
                    Timestamp realTime = rs.getTimestamp("real_time");
                    if (realTime != null) {
                        data.setRealTime(realTime.toLocalDateTime());
                    }
                    
                    data.setSigSrcCd(rs.getString("sig_src_cd"));
                    data.setTargetId(rs.getString("target_id"));
                    data.setLat(rs.getDouble("lat"));
                    data.setLon(rs.getDouble("lon"));
                    data.setSog(rs.getBigDecimal("sog"));
                    data.setCog(rs.getBigDecimal("cog"));
                    data.setHeading(getIntegerFromNumeric(rs, "heading"));
                    data.setShipNm(rs.getString("ship_nm"));
                    data.setShipTy(rs.getString("ship_ty"));
                    data.setRot(getIntegerFromNumeric(rs, "rot"));
                    data.setPosacc(getIntegerFromNumeric(rs, "posacc"));
                    data.setSensorId(rs.getString("sensor_id"));
                    data.setBaseStId(rs.getString("base_st_id"));
                    data.setMode(getIntegerFromNumeric(rs, "mode"));
                    data.setGpsSttus(getIntegerFromNumeric(rs, "gps_sttus"));
                    data.setBatterySttus(getIntegerFromNumeric(rs, "battery_sttus"));
                    data.setVtsCd(rs.getString("vts_cd"));
                    data.setMmsi(rs.getString("mmsi"));
                    data.setVpassId(rs.getString("vpass_id"));
                    data.setShipNo(rs.getString("ship_no"));
                    
                    return data;
                }
        );
        
        vesselTrackDataHolder.setData(vesselDataList);
        log.info("Loaded {} vessel track points for {} to {}", 
                vesselDataList.size(), startTime, endTime);
    }
    
    @Override
    public void afterJob(JobExecution jobExecution) {
        vesselTrackDataHolder.clear();
        log.debug("Cleared vessel track data after job completion");
    }
    
    private Integer getIntegerFromNumeric(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        if (value == null || rs.wasNull()) {
            return null;
        }
        
        if (value instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) value).intValue();
        } else if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
}
