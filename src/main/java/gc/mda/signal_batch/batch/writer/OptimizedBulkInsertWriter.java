package gc.mda.signal_batch.batch.writer;

import gc.mda.signal_batch.domain.gis.model.TileStatistics;
import gc.mda.signal_batch.batch.processor.AreaStatisticsProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import javax.sql.DataSource;
import java.io.*;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;



@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizedBulkInsertWriter {

    @Qualifier("queryDataSource")
    private final DataSource queryDataSource;

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    private final ObjectMapper objectMapper = createObjectMapper();
    
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setDateFormat(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
        mapper.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        return mapper;
    }

    @Value("${vessel.batch.bulk-insert.batch-size:50000}")
    private int batchSize;

    @Value("${vessel.batch.bulk-insert.parallel-threads:4}")
    private int parallelThreads;

    @Value("${vessel.batch.bulk-insert.use-binary-copy:false}")
    private boolean useBinaryCopy;

    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2)
    );

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * TileStatistics Bulk Writer
     */
    public ItemWriter<List<TileStatistics>> tileStatisticsBulkWriter() {
        return new ItemWriter<List<TileStatistics>>() {
            @Override
            public void write(Chunk<? extends List<TileStatistics>> chunk) throws Exception {
                List<TileStatistics> allStats = chunk.getItems().stream()
                        .flatMap(List::stream)
                        .collect(Collectors.toList());

                if (allStats.isEmpty()) {
                    return;
                }

                StopWatch stopWatch = new StopWatch();
                stopWatch.start();

                try {
                    // 파티션별로 그룹화
                    Map<LocalDate, List<TileStatistics>> partitionedData =
                            allStats.stream()
                                    .collect(Collectors.groupingBy(
                                            stat -> stat.getTimeBucket().toLocalDate()
                                    ));

                    // 병렬 처리
                    List<CompletableFuture<BulkInsertResult>> futures = new ArrayList<>();

                    for (Map.Entry<LocalDate, List<TileStatistics>> entry : partitionedData.entrySet()) {
                        LocalDate date = entry.getKey();
                        List<TileStatistics> data = entry.getValue();

                        // 배치 크기로 분할
                        Lists.partition(data, batchSize).forEach(batch -> {
                            CompletableFuture<BulkInsertResult> future = CompletableFuture.supplyAsync(() ->
                                    insertTileStatisticsBatch(date, batch), executorService
                            );
                            futures.add(future);
                        });
                    }

                    // 모든 작업 완료 대기
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    // 결과 집계
                    long totalInserted = futures.stream()
                            .map(CompletableFuture::join)
                            .mapToLong(result -> result.rowsInserted)
                            .sum();

                    stopWatch.stop();
                    log.info("Bulk inserted {} tile statistics in {} ms",
                            totalInserted, stopWatch.getTotalTimeMillis());

                } catch (Exception e) {
                    // CompletionException에서 실제 원인 확인
                    Throwable cause = e;
                    if (e instanceof CompletionException && e.getCause() != null) {
                        cause = e.getCause();
                        if (cause instanceof RuntimeException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                    }
                    
                    // 중복 키 오류는 정상적인 상황
                    if (cause.getMessage() != null && cause.getMessage().contains("중복된 키")) {
                        log.debug("Duplicate key errors detected during bulk insert, using fallback UPSERT");
                    } else {
                        log.error("Bulk insert failed, falling back to batch insert", e);
                    }
                    
                    // 새로운 트랜잭션에서 재시도
                    try {
                        fallbackBatchInsert(allStats);
                    } catch (Exception fallbackEx) {
                        log.error("Fallback insert also failed", fallbackEx);
                        throw fallbackEx;
                    }
                }
            }
        };
    }

    /**
     * 개별 배치 처리
     */
    private BulkInsertResult insertTileStatisticsBatch(LocalDate date,
                                                       List<TileStatistics> batch) {

        String tableName = "t_tile_summary_" + date.format(DateTimeFormatter.BASIC_ISO_DATE);

        // 파티션 존재 확인
        if (!checkTableExists(tableName)) {
            tableName = "t_tile_summary"; // 기본 테이블 사용
        }

        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);

            if (useBinaryCopy) {
                return binaryCopyInsert(copyManager, tableName, batch);
            } else {
                return textCopyInsert(copyManager, tableName, batch);
            }

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                // 중복 키는 정상적인 상황이므로 DEBUG 레벨로 기록
                log.debug("Duplicate entries detected for table {} - switching to UPSERT mode", tableName);
                // 새로운 트랜잭션에서 UPSERT 실행
                try {
                    return upsertBatch(tableName, batch);
                } catch (Exception upsertEx) {
                    log.error("UPSERT also failed for table {}", tableName, upsertEx);
                    throw new RuntimeException("Both COPY and UPSERT failed", upsertEx);
                }
            }
            log.error("Failed to insert batch for table {}", tableName, e);
            throw new RuntimeException("Batch insert failed", e);
        }
    }

    /**
     * 텍스트 기반 COPY
     */
    private BulkInsertResult textCopyInsert(CopyManager copyManager, String tableName,
                                            List<TileStatistics> batch) throws Exception {

        String copySql = String.format("""
            COPY signal.%s (
                tile_id, tile_level, time_bucket, vessel_count,
                unique_vessels, total_points, avg_sog, max_sog,
                vessel_density, created_at
            ) FROM STDIN
            """, tableName);

        try (PipedOutputStream pos = new PipedOutputStream();
             PipedInputStream pis = new PipedInputStream(pos, 1024 * 1024); // 1MB 버퍼
             PrintWriter writer = new PrintWriter(new BufferedWriter(
                     new OutputStreamWriter(pos, "UTF-8"), 65536))) { // 64KB 버퍼

            // 비동기로 데이터 쓰기
            CompletableFuture<Void> writerFuture = CompletableFuture.runAsync(() -> {
                try {
                    for (TileStatistics stat : batch) {
                        writer.println(formatCsvLine(stat));
                    }
                } finally {
                    writer.close();
                }
            });

            // COPY 실행
            long rowsInserted = copyManager.copyIn(copySql, pis);

            // Writer 완료 대기
            writerFuture.join();

            return new BulkInsertResult(rowsInserted, null);
        }
    }

    /**
     * 바이너리 기반 COPY (더 빠름)
     */
    private BulkInsertResult binaryCopyInsert(CopyManager copyManager, String tableName,
                                              List<TileStatistics> batch) throws Exception {

        String copySql = String.format("""
            COPY signal.%s (
                tile_id, tile_level, time_bucket, vessel_count,
                unique_vessels, total_points, avg_sog, max_sog,
                vessel_density, created_at
            ) FROM STDIN WITH (FORMAT BINARY)
            """, tableName);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // PostgreSQL 바이너리 형식 헤더
            writeBinaryHeader(baos);

            // 데이터 쓰기
            for (TileStatistics stat : batch) {
                writeBinaryRow(baos, stat);
            }

            // 트레일러
            writeBinaryTrailer(baos);

            // COPY 실행
            try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
                long rowsInserted = copyManager.copyIn(copySql, bais);
                return new BulkInsertResult(rowsInserted, null);
            }
        }
    }

    /**
     * CSV 라인 포맷팅
     */
    private String formatCsvLine(TileStatistics stat) {
        String json = convertToJson(stat.getUniqueVessels());
        // TEXT 형식에서는 탭과 줄바꿈만 이스케이프
        String escapedJson = json.replace("\\", "\\\\")
                                 .replace("\t", "\\t")
                                 .replace("\n", "\\n")
                                 .replace("\r", "\\r");
        
        return String.format("%s\t%d\t%s\t%d\t%s\t%d\t%s\t%s\t%s\t%s",
                stat.getTileId(),
                stat.getTileLevel(),
                stat.getTimeBucket().format(TIMESTAMP_FORMATTER),
                stat.getVesselCount(),
                escapedJson,
                stat.getTotalPoints(),
                stat.getAvgSog() != null ? stat.getAvgSog().toString() : "\\N",
                stat.getMaxSog() != null ? stat.getMaxSog().toString() : "\\N",
                stat.getVesselDensity() != null ? stat.getVesselDensity().toString() : "\\N",
                LocalDateTime.now().format(TIMESTAMP_FORMATTER)
        );
    }

    /**
     * CSV 특수문자 이스케이프
     */
    @SuppressWarnings("unused")
    private String escapeCsv(String value) {
        if (value == null) return "NULL";
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\"", "\\\"");
    }

    /**
     * JSON 이스케이프
     */
    @SuppressWarnings("unused")
    private String escapeJson(String json) {
        if (json == null) return "NULL";
        return json.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }



    /**
     * 객체를 JSON으로 변환
     */
    private String convertToJson(Object obj) {
        try {
            if (obj == null) return "{}";
            
            // 클래스 레벨의 objectMapper 사용
            String json = objectMapper.writeValueAsString(obj);
            
            // JSON 검증 로그
            if (log.isDebugEnabled()) {
                log.debug("Generated JSON: {}", json);
            }
            
            return json;
        } catch (Exception e) {
            log.error("Error converting to JSON: {}", obj, e);
            return "{}";
        }
    }

    /**
     * UPSERT 배치 처리 (중복키 발생 시)
     */
    private BulkInsertResult upsertBatch(String tableName, List<TileStatistics> batch) {
        // 항상 tile_level도 포함하여 처리
        String sql = String.format("""
            INSERT INTO signal.%s (
                tile_id, tile_level, time_bucket, vessel_count,
                unique_vessels, total_points, avg_sog, max_sog,
                vessel_density, created_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (tile_id, time_bucket, tile_level) DO UPDATE SET
                vessel_count = EXCLUDED.vessel_count,
                unique_vessels = EXCLUDED.unique_vessels,
                total_points = EXCLUDED.total_points,
                avg_sog = EXCLUDED.avg_sog,
                max_sog = EXCLUDED.max_sog,
                vessel_density = EXCLUDED.vessel_density,
                created_at = EXCLUDED.created_at
        """, tableName);

        long totalUpdated = 0;
        
        // 배치 크기로 분할
        for (List<TileStatistics> partition : Lists.partition(batch, 1000)) {
            List<Object[]> args = partition.stream()
                .map(stat -> new Object[] {
                    stat.getTileId(),
                    stat.getTileLevel(),
                    Timestamp.valueOf(stat.getTimeBucket()),
                    stat.getVesselCount(),
                    convertToJson(stat.getUniqueVessels()),
                    stat.getTotalPoints(),
                    stat.getAvgSog(),
                    stat.getMaxSog(),
                    stat.getVesselDensity(),
                    Timestamp.valueOf(LocalDateTime.now())
                })
                .collect(Collectors.toList());
            
            int[] results = queryJdbcTemplate.batchUpdate(sql, args);
            
            for (int result : results) {
                totalUpdated += result;
            }
        }
        
        log.info("Upserted {} records in table {}", totalUpdated, tableName);
        return new BulkInsertResult(totalUpdated, null);
    }

    /**
     * Fallback 배치 인서트
     */
    private void fallbackBatchInsert(List<TileStatistics> stats) {
        String sql = """
            INSERT INTO signal.t_tile_summary (
                tile_id, tile_level, time_bucket, vessel_count,
                unique_vessels, total_points, avg_sog, max_sog,
                vessel_density, created_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (tile_id, time_bucket, tile_level) DO UPDATE SET
                vessel_count = EXCLUDED.vessel_count,
                unique_vessels = EXCLUDED.unique_vessels,
                total_points = EXCLUDED.total_points,
                avg_sog = EXCLUDED.avg_sog,
                max_sog = EXCLUDED.max_sog,
                vessel_density = EXCLUDED.vessel_density,
                created_at = EXCLUDED.created_at
        """;

        // 배치 크기로 분할
        Lists.partition(stats, 1000).forEach(batch -> {
            List<Object[]> args = batch.stream()
                .map(stat -> new Object[] {
                    stat.getTileId(),
                    stat.getTileLevel(),
                    Timestamp.valueOf(stat.getTimeBucket()),
                    stat.getVesselCount(),
                    convertToJson(stat.getUniqueVessels()),
                    stat.getTotalPoints(),
                    stat.getAvgSog(),
                    stat.getMaxSog(),
                    stat.getVesselDensity(),
                    Timestamp.valueOf(LocalDateTime.now())
                })
                .collect(Collectors.toList());
            
            queryJdbcTemplate.batchUpdate(sql, args);
        });
    }

    /**
     * AreaStatistics Bulk Writer
     */
    public ItemWriter<List<AreaStatisticsProcessor.AreaStatistics>>
    areaStatisticsBulkWriter() {

        return new ItemWriter<List<AreaStatisticsProcessor.AreaStatistics>>() {
            @Override
            public void write(Chunk<? extends List<AreaStatisticsProcessor.AreaStatistics>> chunk)
                    throws Exception {

                List<AreaStatisticsProcessor.AreaStatistics> allStats =
                        chunk.getItems().stream()
                                .flatMap(List::stream)
                                .collect(Collectors.toList());

                if (allStats.isEmpty()) {
                    return;
                }

                // 배치 크기로 분할하여 병렬 처리
                Lists.partition(allStats, batchSize)
                        .parallelStream()
                        .forEach(batch -> insertAreaStatisticsBatch(batch));
            }
        };
    }

    private void insertAreaStatisticsBatch(
            List<AreaStatisticsProcessor.AreaStatistics> batch) {

        try (Connection conn = queryDataSource.getConnection()) {
            BaseConnection baseConn = conn.unwrap(BaseConnection.class);
            CopyManager copyManager = new CopyManager(baseConn);

            String copySql = """
                COPY signal.t_area_statistics (
                    area_id, time_bucket, vessel_count,
                    in_count, out_count, transit_vessels,
                    stationary_vessels, avg_sog, created_at
                ) FROM STDIN WITH (FORMAT CSV, DELIMITER '|', NULL 'NULL')
            """;

            StringWriter writer = new StringWriter();
            for (var stat : batch) {
                writer.write(String.format("%s|%s|%d|%d|%d|%s|%s|%s|%s\n",
                        stat.getAreaId(),
                        stat.getTimeBucket().format(TIMESTAMP_FORMATTER),
                        stat.getVesselCount(),
                        stat.getInCount(),
                        stat.getOutCount(),
                        escapeJson(convertToJson(stat.getTransitVessels())),
                        escapeJson(convertToJson(stat.getStationaryVessels())),
                        stat.getAvgSog() != null ? stat.getAvgSog().toString() : "NULL",
                        LocalDateTime.now().format(TIMESTAMP_FORMATTER)
                ));
            }

            long rowsInserted = copyManager.copyIn(copySql, new StringReader(writer.toString()));
            log.debug("Inserted {} area statistics", rowsInserted);

        } catch (Exception e) {
            log.error("Failed to bulk insert area statistics", e);
            // Fallback 처리
        }
    }

    /**
     * 테이블 존재 확인
     */
    private boolean checkTableExists(String tableName) {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'signal' AND tablename = ?)";
        return Boolean.TRUE.equals(queryJdbcTemplate.queryForObject(sql, Boolean.class, tableName));
    }


    /**
     * 바이너리 형식 헬퍼 메소드들
     */
    private void writeBinaryHeader(ByteArrayOutputStream baos) throws IOException {
        // PostgreSQL 바이너리 COPY 헤더
        baos.write("PGCOPY\n\377\r\n\0".getBytes("UTF-8"));
        // 플래그
        writeInt32(baos, 0);
        // 헤더 확장 길이
        writeInt32(baos, 0);
    }

    private void writeBinaryTrailer(ByteArrayOutputStream baos) throws IOException {
        // -1 표시 (EOF)
        writeInt16(baos, -1);
    }

    private void writeBinaryRow(ByteArrayOutputStream baos,
                                TileStatistics stat) throws IOException {
        // 필드 수
        writeInt16(baos, 10);

        // 각 필드 쓰기
        writeString(baos, stat.getTileId());
        writeInt32(baos, stat.getTileLevel());
        writeTimestamp(baos, stat.getTimeBucket());
        writeInt32(baos, stat.getVesselCount());
        writeString(baos, convertToJson(stat.getUniqueVessels()));
        writeInt64(baos, stat.getTotalPoints());
        writeBigDecimal(baos, stat.getAvgSog());
        writeBigDecimal(baos, stat.getMaxSog());
        writeBigDecimal(baos, stat.getVesselDensity());
        writeTimestamp(baos, LocalDateTime.now());
    }

    private void writeInt16(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }

    private void writeInt32(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write((value >> 24) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }

    private void writeInt64(ByteArrayOutputStream baos, long value) throws IOException {
        for (int i = 56; i >= 0; i -= 8) {
            baos.write((int)(value >> i) & 0xFF);
        }
    }

    private void writeString(ByteArrayOutputStream baos, String value) throws IOException {
        if (value == null) {
            writeInt32(baos, -1); // NULL
        } else {
            byte[] bytes = value.getBytes("UTF-8");
            writeInt32(baos, bytes.length);
            baos.write(bytes);
        }
    }

    private void writeTimestamp(ByteArrayOutputStream baos, LocalDateTime value) throws IOException {
        if (value == null) {
            writeInt32(baos, -1); // NULL
        } else {
            // PostgreSQL timestamp 형식으로 변환
            long micros = value.atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli() * 1000;
            writeInt32(baos, 8); // 길이
            writeInt64(baos, micros);
        }
    }

    private void writeBigDecimal(ByteArrayOutputStream baos, java.math.BigDecimal value)
            throws IOException {
        if (value == null) {
            writeInt32(baos, -1); // NULL
        } else {
            writeString(baos, value.toString());
        }
    }

    /**
     * 결과 클래스
     */
    private static class BulkInsertResult {
        final long rowsInserted;
        @SuppressWarnings("unused")
        final String error;

        BulkInsertResult(long rowsInserted, String error) {
            this.rowsInserted = rowsInserted;
            this.error = error;
        }
    }

    /**
     * 리소스 정리
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}