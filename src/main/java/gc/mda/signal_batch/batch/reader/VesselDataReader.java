package gc.mda.signal_batch.batch.reader;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class VesselDataReader {

    private final DataSource collectDataSource;
    private final JdbcTemplate collectJdbcTemplate;

    private static final DateTimeFormatter PARTITION_FORMATTER = DateTimeFormatter.ofPattern("yyMMdd");

    public VesselDataReader(
            @Qualifier("collectDataSource") DataSource collectDataSource,
            @Qualifier("collectJdbcTemplate") JdbcTemplate collectJdbcTemplate) {
        this.collectDataSource = collectDataSource;
        this.collectJdbcTemplate = collectJdbcTemplate;
    }

    @PostConstruct
    public void init() {
        logDataSourceInfo();
    }

    /**
     * 최신 위치만 가져오는 최적화된 Reader
     * DISTINCT ON을 사용하여 각 선박의 최신 위치만 조회
     */
    public JdbcCursorItemReader<VesselData> vesselLatestPositionReader(
            LocalDateTime startTime,
            LocalDateTime endTime,
            String partition) {

        log.info("Creating optimized latest position reader from {} to {}", startTime, endTime);

        JdbcCursorItemReader<VesselData> reader = new JdbcCursorItemReader<VesselData>() {
            @Override
            protected void openCursor(Connection con) {
                try {
                    // search_path 설정
                    try (var stmt = con.createStatement()) {
                        stmt.execute("SET search_path TO signal, public");
                    }
                } catch (Exception e) {
                    log.error("Error setting search_path in cursor", e);
                    throw new RuntimeException("Failed to set search_path", e);
                }
                super.openCursor(con);
            }
        };

        reader.setDataSource(collectDataSource);
        reader.setName("vesselLatestPositionReader");

        // 성능 최적화 설정
        reader.setFetchSize(10000);  // 줄임 (최신 위치만 가져오므로)
        reader.setMaxRows(0);
        reader.setQueryTimeout(300);
        reader.setVerifyCursorPosition(false);
        reader.setUseSharedExtendedConnection(false);
        reader.setSaveState(false);

        String tableName = determineTableName(partition, startTime);
        log.info("Using table: {}", tableName);

        // 최신 위치만 가져오는 SQL - DISTINCT ON 사용
        String sql = """
            SELECT DISTINCT ON (sig_src_cd, target_id)
                message_time, real_time, sig_src_cd, target_id,
                lat, lon, sog, cog, heading, ship_nm, ship_ty, rot, posacc,
                sensor_id, base_st_id, mode, gps_sttus, battery_sttus,
                vts_cd, mmsi, vpass_id, ship_no
            FROM signal.%s
            WHERE message_time >= ? AND message_time < ? 
                AND sig_src_cd != '000005'
                AND lat BETWEEN -90 AND 90 
                AND lon BETWEEN -180 AND 180
            ORDER BY sig_src_cd, target_id, message_time DESC
            """.formatted(tableName);

        reader.setSql(sql);

        reader.setPreparedStatementSetter(ps -> {
            ps.setObject(1, Timestamp.valueOf(startTime));
            ps.setObject(2, Timestamp.valueOf(endTime));
        });

        reader.setRowMapper(new OptimizedVesselDataRowMapper());

        // 예상 데이터 건수 로그
        try {
            Integer expectedCount = collectJdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT DISTINCT ON (sig_src_cd, target_id) 1
                        FROM signal.%s
                        WHERE message_time >= ? AND message_time < ?
                            AND sig_src_cd != '000005'
                    ) t
                    """.formatted(tableName),
                    Integer.class,
                    startTime, endTime
            );
            log.info("Expected record count (latest positions only): {}", expectedCount);
        } catch (Exception e) {
            log.warn("Could not get expected count: {}", e.getMessage());
        }

        return reader;
    }

    /**
     * 기존 Cursor Reader (전체 데이터) - 타일 집계 등에 필요한 경우
     */
    public JdbcCursorItemReader<VesselData> vesselDataCursorReader(
            LocalDateTime startTime,
            LocalDateTime endTime,
            String partition) {

        log.info("Creating cursor reader for partition: {} from {} to {}",
                partition, startTime, endTime);

        JdbcCursorItemReader<VesselData> reader = new JdbcCursorItemReader<VesselData>() {
            @Override
            protected void openCursor(Connection con) {
                try {
                    try (var stmt = con.createStatement()) {
                        stmt.execute("SET search_path TO signal, public");
                    }
                } catch (Exception e) {
                    log.error("Error setting search_path in cursor", e);
                    throw new RuntimeException("Failed to set search_path", e);
                }
                super.openCursor(con);
            }
        };

        reader.setDataSource(collectDataSource);
        reader.setName("vesselDataCursorReader");

        reader.setFetchSize(50000);
        reader.setMaxRows(0);
        reader.setQueryTimeout(1800);
        reader.setVerifyCursorPosition(false);
        reader.setUseSharedExtendedConnection(false);
        reader.setSaveState(false);

        String tableName = determineTableName(partition, startTime);
        log.info("Determined table name: {} for startTime: {}", tableName, startTime);

        // 전체 데이터 조회 SQL (타일 집계용)
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT /*+ PARALLEL(8) */ ");
        sql.append("message_time, real_time, sig_src_cd, target_id, ");
        sql.append("lat, lon, sog, cog, heading, ship_nm, ship_ty, rot, posacc, ");
        sql.append("sensor_id, base_st_id, mode, gps_sttus, battery_sttus, ");
        sql.append("vts_cd, mmsi, vpass_id, ship_no ");
        sql.append("FROM signal.").append(tableName).append(" ");
        sql.append("WHERE message_time >= ? AND message_time < ? AND sig_src_cd != '000005' ");
        sql.append("ORDER BY message_time, sig_src_cd, target_id");

        reader.setSql(sql.toString());

        reader.setPreparedStatementSetter(ps -> {
            ps.setTimestamp(1, Timestamp.valueOf(startTime));
            ps.setTimestamp(2, Timestamp.valueOf(endTime));
        });

        reader.setRowMapper(new OptimizedVesselDataRowMapper());

        return reader;
    }

    /**
     * 기존 Paging Reader (작은 데이터셋용)
     */
    public JdbcPagingItemReader<VesselData> vesselDataPagingReader(
            LocalDateTime startTime,
            LocalDateTime endTime,
            String partition) {

        JdbcPagingItemReader<VesselData> reader = new JdbcPagingItemReader<>();
        reader.setDataSource(collectDataSource);
        reader.setPageSize(10000);
        reader.setFetchSize(10000);
        reader.setRowMapper(new OptimizedVesselDataRowMapper());

        String tableName = determineTableName(partition, startTime);

        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("SELECT message_time, real_time, sig_src_cd, target_id, " +
                "lat, lon, sog, cog, heading, ship_nm, ship_ty, rot, posacc, " +
                "sensor_id, base_st_id, mode, gps_sttus, battery_sttus, " +
                "vts_cd, mmsi, vpass_id, ship_no ");

        queryProvider.setFromClause("FROM signal." + tableName);
        queryProvider.setWhereClause("WHERE message_time >= :startTime AND message_time < :endTime and sig_src_cd != '000005'");

        Map<String, Order> sortKeys = new HashMap<>();
        sortKeys.put("message_time", Order.ASCENDING);
        sortKeys.put("sig_src_cd", Order.ASCENDING);
        sortKeys.put("target_id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        reader.setQueryProvider(queryProvider);

        Map<String, Object> parameterValues = new HashMap<>();
        parameterValues.put("startTime", startTime);
        parameterValues.put("endTime", endTime);
        reader.setParameterValues(parameterValues);

        try {
            reader.afterPropertiesSet();
        } catch (Exception e) {
            log.error("Failed to initialize JdbcPagingItemReader", e);
            throw new RuntimeException("Reader initialization failed", e);
        }

        return reader;
    }

    /**
     * 파티션 테이블 이름 결정
     */
    private String determineTableName(String partition, LocalDateTime startTime) {
        if (partition != null && !partition.isEmpty()) {
            log.debug("Using specified partition: {}", partition);
            return partition;
        }

        LocalDateTime targetTime = startTime != null ? startTime : LocalDateTime.now();
        String partitionSuffix = targetTime.format(PARTITION_FORMATTER);
        String tableName = "sig_test_" + partitionSuffix;

        try {
            Boolean exists = collectJdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'signal' AND tablename = ?)",
                    Boolean.class,
                    tableName
            );

            if (Boolean.TRUE.equals(exists)) {
                log.info("Auto-selected partition table: {}", tableName);
                return tableName;
            } else {
                log.warn("Partition table {} does not exist, using sig_test", tableName);
                return "sig_test";
            }
        } catch (Exception e) {
            log.error("Error checking partition table existence", e);
            return "sig_test";
        }
    }

    /**
     * 최적화된 RowMapper
     */
    public static class OptimizedVesselDataRowMapper implements RowMapper<VesselData> {
        @Override
        public VesselData mapRow(ResultSet rs, int rowNum) throws SQLException {
            VesselData data = new VesselData();

            Timestamp messageTime = rs.getTimestamp(1);
            if (messageTime != null) {
                data.setMessageTime(messageTime.toLocalDateTime());
            }

            Timestamp realTime = rs.getTimestamp(2);
            if (realTime != null) {
                data.setRealTime(realTime.toLocalDateTime());
            }

            data.setSigSrcCd(rs.getString(3));
            data.setTargetId(rs.getString(4));
            data.setLat(rs.getDouble(5));
            data.setLon(rs.getDouble(6));
            data.setSog(rs.getBigDecimal(7));
            data.setCog(rs.getBigDecimal(8));

            data.setHeading(getIntegerFromNumeric(rs, 9));
            data.setShipNm(rs.getString(10));
            data.setShipTy(rs.getString(11));
            data.setRot(getIntegerFromNumeric(rs, 12));
            data.setPosacc(getIntegerFromNumeric(rs, 13));
            data.setSensorId(rs.getString(14));
            data.setBaseStId(rs.getString(15));
            data.setMode(getIntegerFromNumeric(rs, 16));
            data.setGpsSttus(getIntegerFromNumeric(rs, 17));
            data.setBatterySttus(getIntegerFromNumeric(rs, 18));
            data.setVtsCd(rs.getString(19));
            data.setMmsi(rs.getString(20));
            data.setVpassId(rs.getString(21));
            data.setShipNo(rs.getString(22));

            return data;
        }

        private Integer getIntegerFromNumeric(ResultSet rs, int columnIndex) throws SQLException {
            Object value = rs.getObject(columnIndex);
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

    private void logDataSourceInfo() {
        try {
            String info = getDataSourceInfo(collectDataSource);
            log.info("VesselDataReader initialized with DataSource: {}", info);
        } catch (Exception e) {
            log.error("Failed to get DataSource info", e);
        }
    }

    private String getDataSourceInfo(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String url = meta.getURL();
            String user = meta.getUserName();
            String db = conn.getCatalog();
            String schema = conn.getSchema();
            return String.format("URL=%s, User=%s, DB=%s, Schema=%s", url, user, db, schema);
        } catch (Exception e) {
            return "Unknown (" + e.getMessage() + ")";
        }
    }

    @SuppressWarnings("unused")
    private void testConnection(String tableName) {
        try {
            try (Connection conn = collectDataSource.getConnection()) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO signal, public");
                }

                String testSql = "SELECT COUNT(*) FROM signal." + tableName + " LIMIT 1";
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery(testSql)) {
                    if (rs.next()) {
                        log.info("Direct connection test successful, count: {}", rs.getInt(1));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Connection test failed", e);
        }
    }
}