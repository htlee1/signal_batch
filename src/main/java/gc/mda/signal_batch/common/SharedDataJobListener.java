package gc.mda.signal_batch.common;

import gc.mda.signal_batch.model.VesselData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SharedDataJobListener implements JobExecutionListener {
    
    private final JdbcTemplate collectJdbcTemplate;
    private final VesselDataHolder dataHolder;
    
    @Override
    public void beforeJob(JobExecution jobExecution) {
        try {
            LocalDateTime startTime = LocalDateTime.parse(
                jobExecution.getJobParameters().getString("startTime")
            );
            LocalDateTime endTime = LocalDateTime.parse(
                jobExecution.getJobParameters().getString("endTime")
            );
            
            // 최신 위치 데이터 한 번만 로드
            String sql = """
                WITH latest_positions AS (
                    SELECT DISTINCT ON (sig_src_cd, target_id)
                        sig_src_cd, target_id, lat, lon,
                        sog, cog, heading, ship_nm, ship_ty,
                        message_time, real_time, mmsi, vpass_id, ship_no
                    FROM signal.sig_test
                    WHERE message_time >= ? AND message_time < ?
                    AND sig_src_cd != '000005'
                    AND length(target_id) > 5
                    ORDER BY sig_src_cd, target_id, message_time DESC
                )
                SELECT * FROM latest_positions
            """;
            
            List<VesselData> vesselData = collectJdbcTemplate.query(sql, 
                ps -> {
                    ps.setTimestamp(1, Timestamp.valueOf(startTime));
                    ps.setTimestamp(2, Timestamp.valueOf(endTime));
                },
                (rs, rowNum) -> {
                    VesselData data = VesselData.builder()
                        .sigSrcCd(rs.getString("sig_src_cd"))
                        .targetId(rs.getString("target_id"))
                        .lat(rs.getDouble("lat"))
                        .lon(rs.getDouble("lon"))
                        .sog(rs.getBigDecimal("sog"))
                        .cog(rs.getBigDecimal("cog"))
                        .shipNm(rs.getString("ship_nm"))
                        .shipTy(rs.getString("ship_ty"))
                        .messageTime(rs.getTimestamp("message_time").toLocalDateTime())
                        .realTime(rs.getTimestamp("real_time").toLocalDateTime())
                        .mmsi(rs.getString("mmsi"))
                        .vpassId(rs.getString("vpass_id"))
                        .shipNo(rs.getString("ship_no"))
                        .build();
                    
                    // heading 처리 - numeric 타입을 Integer로 변환
                    Object headingValue = rs.getObject("heading");
                    if (headingValue != null && !rs.wasNull()) {
                        if (headingValue instanceof java.math.BigDecimal) {
                            data.setHeading(((java.math.BigDecimal) headingValue).intValue());
                        } else if (headingValue instanceof Number) {
                            data.setHeading(((Number) headingValue).intValue());
                        }
                    }
                    
                    return data;
                }
            );
            
            dataHolder.setData(vesselData);
            jobExecution.getExecutionContext().putInt("totalCount", vesselData.size());
            
            log.info("Loaded {} vessel positions for job execution", vesselData.size());
            
        } catch (Exception e) {
            log.error("Failed to load vessel data", e);
            throw new RuntimeException("Failed to load vessel data", e);
        }
    }
    
    @Override
    public void afterJob(JobExecution jobExecution) {
        dataHolder.clear();
        log.info("Cleared vessel data from memory");
    }
}
