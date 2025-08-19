package gc.mda.signal_batch.global.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManager {

    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    @Value("${vessel.batch.partition.retention-days:30}")
    private int retentionDays;

    @Value("${vessel.batch.partition.future-days:7}")
    private int futureDays;

    @Value("${vessel.batch.enable-partition-auto-management:true}")
    private boolean enableAutoManagement;

    private static final DateTimeFormatter PARTITION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter PARTITION_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM");

    // 일별 파티션 테이블 목록
    private static final List<String> DAILY_PARTITION_TABLES = List.of(
            // CollectDB 테이블
            "sig_test",
            // QueryDB 5분 단위 테이블
            "t_vessel_tracks_5min",
            "t_grid_vessel_tracks",
            "t_grid_tracks_summary",
            "t_area_vessel_tracks",
            "t_area_tracks_summary",
            "t_tile_summary",
            "t_area_statistics"
    );

    // 월별 파티션 테이블 목록
    private static final List<String> MONTHLY_PARTITION_TABLES = List.of(
            "t_vessel_tracks_hourly",
            "t_grid_vessel_tracks_hourly",
            "t_grid_tracks_summary_hourly",
            "t_area_tracks_summary_hourly",
            "t_vessel_tracks_daily",
            "t_abnormal_tracks"
    );

    /**
     * 애플리케이션 시작 시 파티션 초기화
     */
    @PostConstruct
    public void initialize() {
        log.info("========== PartitionManager Initialization ==========");

        // DataSource 정보 로깅
        log.info("=== Collect DataSource Info ===");
        DataSourceLogger.logJdbcTemplateInfo("PartitionManager-Collect", collectJdbcTemplate);

        log.info("=== Query DataSource Info ===");
        DataSourceLogger.logJdbcTemplateInfo("PartitionManager-Query", queryJdbcTemplate);

        if (!enableAutoManagement) {
            log.info("Partition auto-management is disabled");
            log.info("========== End of Initialization ==========");
            return;
        }

        log.info("Initializing partition management...");
        try {
            // 현재 존재하는 테이블 확인
            checkExistingTables();

            // 일별 파티션 생성
            createDailyPartitions(LocalDate.now(), futureDays);

            // 월별 파티션 생성
            createMonthlyPartitions(LocalDate.now());

            // 파티션 상태 리포트
            reportPartitionStatus();
        } catch (Exception e) {
            log.error("Failed to initialize partitions", e);
        }

        log.info("========== End of Initialization ==========");
    }

    /**
     * 매일 자정에 실행되는 파티션 관리 작업
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void managePartitions() {
        if (!enableAutoManagement) {
            return;
        }

        log.info("Starting scheduled partition management");

        try {
            LocalDate today = LocalDate.now();

            // 1. 일별 파티션 생성
            createDailyPartitions(today, futureDays);

            // 2. 월별 파티션 생성 (매월 1일에 다음달 파티션 생성)
            if (today.getDayOfMonth() == 1) {
                createMonthlyPartitions(today.plusMonths(1));
            }

            // 3. 오래된 파티션 삭제
            dropOldPartitions(today.minusDays(retentionDays));

            // 4. 파티션 통계 업데이트
            updatePartitionStatistics();

            // 5. 상태 리포트
            reportPartitionStatus();

            log.info("Partition management completed successfully");

        } catch (Exception e) {
            log.error("Failed to manage partitions", e);
        }
    }

    /**
     * 현재 존재하는 테이블 확인
     */
    private void checkExistingTables() {
        log.info("Checking existing tables...");

        // CollectDB 테이블 확인
        for (String table : DAILY_PARTITION_TABLES) {
            if (table.equals("sig_test")) {
                checkTableExists("signal", table, collectJdbcTemplate, "CollectDB");
            }
        }

        // QueryDB 테이블 확인
        for (String table : DAILY_PARTITION_TABLES) {
            if (!table.equals("sig_test")) {
                checkTableExists("signal", table, queryJdbcTemplate, "QueryDB");
            }
        }

        for (String table : MONTHLY_PARTITION_TABLES) {
            checkTableExists("signal", table, queryJdbcTemplate, "QueryDB");
        }
    }

    private void checkTableExists(String schema, String table, JdbcTemplate jdbcTemplate, String dbType) {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schema, table);

        if (Boolean.TRUE.equals(exists)) {
            log.info("[{}] Table exists: {}.{}", dbType, schema, table);
        } else {
            log.warn("[{}] Table NOT found: {}.{}", dbType, schema, table);
        }
    }

    /**
     * 일별 파티션 생성
     */
    private void createDailyPartitions(LocalDate startDate, int days) {
        log.info("Creating daily partitions for {} days starting from {}", days, startDate);

        List<PartitionTask> tasks = new ArrayList<>();

        IntStream.range(0, days).forEach(offset -> {
            LocalDate targetDate = startDate.plusDays(offset);

            for (String table : DAILY_PARTITION_TABLES) {
                if (table.equals("sig_test")) {
                    // CollectDB 테이블
                    tasks.add(new PartitionTask("signal", table, targetDate, collectJdbcTemplate, "daily"));
                } else {
                    // QueryDB 테이블
                    tasks.add(new PartitionTask("signal", table, targetDate, queryJdbcTemplate, "daily"));
                }
            }
        });

        // 병렬 처리
        tasks.parallelStream().forEach(this::createPartition);
    }

    /**
     * 월별 파티션 생성
     */
    private void createMonthlyPartitions(LocalDate targetMonth) {
        log.info("Creating monthly partitions for {}", targetMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")));

        List<PartitionTask> tasks = new ArrayList<>();

        for (String table : MONTHLY_PARTITION_TABLES) {
            tasks.add(new PartitionTask("signal", table, targetMonth, queryJdbcTemplate, "monthly"));
        }

        // 병렬 처리
        tasks.parallelStream().forEach(this::createPartition);
    }

    /**
     * 개별 파티션 생성
     */
    private void createPartition(PartitionTask task) {
        String partitionName;
        String createSql;

        if (task.partitionType.equals("daily")) {
            partitionName = task.baseTable + "_" + task.date.format(PARTITION_DATE_FORMAT);
            createSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s.%s PARTITION OF %s.%s
                FOR VALUES FROM ('%s') TO ('%s')
                """,
                    task.schema, partitionName, task.schema, task.baseTable,
                    task.date, task.date.plusDays(1)
            );
        } else {
            // monthly
            partitionName = task.baseTable + "_" + task.date.format(PARTITION_MONTH_FORMAT);
            LocalDate firstDayOfMonth = task.date.withDayOfMonth(1);
            LocalDate firstDayOfNextMonth = firstDayOfMonth.plusMonths(1);

            createSql = String.format("""
                CREATE TABLE IF NOT EXISTS %s.%s PARTITION OF %s.%s
                FOR VALUES FROM ('%s') TO ('%s')
                """,
                    task.schema, partitionName, task.schema, task.baseTable,
                    firstDayOfMonth, firstDayOfNextMonth
            );
        }

        String dsType = task.jdbcTemplate == collectJdbcTemplate ? "COLLECT" : "QUERY";

        try {
            // 파티션 존재 확인
            if (partitionExists(task.schema, partitionName, task.jdbcTemplate)) {
                log.debug("[{}] Partition already exists: {}.{}", dsType, task.schema, partitionName);
                return;
            }

            // 파티션 생성
            task.jdbcTemplate.execute(createSql);
            log.info("[{}] Created partition: {}.{}", dsType, task.schema, partitionName);

            // 파티션별 인덱스 생성
            createPartitionIndexes(task.schema, partitionName, task.baseTable, task.jdbcTemplate);

        } catch (Exception e) {
            log.error("[{}] Failed to create partition: {}.{}", dsType, task.schema, partitionName, e);
        }
    }

    /**
     * 파티션별 인덱스 생성
     */
    private void createPartitionIndexes(String schema, String partitionName,
                                        String baseTable, JdbcTemplate jdbcTemplate) {
        List<String> indexSqls = new ArrayList<>();

        // sig_test 테이블
        if (baseTable.contains("sig_test")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_msg_time_idx ON %s.%s (message_time DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_real_time_idx ON %s.%s (real_time DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_sig_target_idx ON %s.%s (sig_src_cd, target_id)",
                    partitionName, schema, partitionName
            ));
        }
        // 5분 궤적 테이블
        else if (baseTable.equals("t_vessel_tracks_5min")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_geom_idx ON %s.%s USING GIST (track_geom)",
                    partitionName, schema, partitionName
            ));
            // 성능 최적화를 위한 복합 인덱스 (WebSocket API 개선)
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_vessel_include_idx ON %s.%s (time_bucket, sig_src_cd, target_id) " +
                    "INCLUDE (distance_nm, avg_speed, max_speed, point_count) WHERE track_geom IS NOT NULL",
                    partitionName, schema, partitionName
            ));
        }
        // 해구별 궤적 테이블
        else if (baseTable.contains("t_grid_vessel_tracks") || baseTable.contains("t_grid_tracks_summary")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_haegu_time_idx ON %s.%s (haegu_no, time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            
            // 선박별 진입 이력 조회를 위한 인덱스 (순서 있는 다중 해구 진입 체크)
            if (baseTable.equals("t_grid_vessel_tracks")) {
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_time_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
                // 진입/퇴출 시간 인덱스
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_entry_exit_idx ON %s.%s (entry_time, exit_time) WHERE entry_time IS NOT NULL",
                        partitionName, schema, partitionName
                ));
            }
        }
        // 구역별 궤적 테이블
        else if (baseTable.contains("t_area_vessel_tracks") || baseTable.contains("t_area_tracks_summary")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_area_time_idx ON %s.%s (area_id, time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            
            // 선박별 진입 이력 조회를 위한 인덱스 (순서 있는 다중 구역 진입 체크)
            if (baseTable.equals("t_area_vessel_tracks")) {
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_time_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
                // 다중 구역 순차 진입 체크를 위한 복합 인덱스
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_area_vessel_time_idx ON %s.%s (area_id, sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
            }
        }
        // 타일 집계 테이블
        else if (baseTable.contains("t_tile_summary")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_tile_time_idx ON %s.%s (tile_id, time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_bucket_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
        }
        // 구역 통계 테이블
        else if (baseTable.contains("t_area_statistics")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_area_time_idx ON %s.%s (area_id, time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
        }
        // 시간별 궤적 테이블
        else if (baseTable.contains("hourly")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            if (baseTable.contains("vessel_tracks")) {
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
                // 성능 최적화를 위한 복합 인덱스 (WebSocket API 개선)
                if (baseTable.equals("t_vessel_tracks_hourly")) {
                    indexSqls.add(String.format(
                            "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_vessel_include_idx ON %s.%s (time_bucket, sig_src_cd, target_id) " +
                            "INCLUDE (distance_nm, avg_speed, max_speed, point_count) WHERE track_geom IS NOT NULL",
                            partitionName, schema, partitionName
                    ));
                    indexSqls.add(String.format(
                            "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_geom_idx ON %s.%s USING GIST (track_geom)",
                            partitionName, schema, partitionName
                    ));
                }
            }
            // 해구별 hourly 테이블 진입 이력 인덱스
            if (baseTable.equals("t_grid_vessel_tracks_hourly")) {
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_time_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
            }
        }
        // 일별 궤적 테이블
        else if (baseTable.contains("daily")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            if (baseTable.contains("vessel_tracks")) {
                indexSqls.add(String.format(
                        "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_idx ON %s.%s (sig_src_cd, target_id, time_bucket DESC)",
                        partitionName, schema, partitionName
                ));
                // 성능 최적화를 위한 복합 인덱스 (WebSocket API 개선)
                if (baseTable.equals("t_vessel_tracks_daily")) {
                    indexSqls.add(String.format(
                            "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_vessel_include_idx ON %s.%s (time_bucket, sig_src_cd, target_id) " +
                            "INCLUDE (distance_nm, avg_speed, max_speed, point_count) WHERE track_geom IS NOT NULL",
                            partitionName, schema, partitionName
                    ));
                    indexSqls.add(String.format(
                            "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_geom_idx ON %s.%s USING GIST (track_geom)",
                            partitionName, schema, partitionName
                    ));
                }
            }
        }
        // 비정상 궤적 테이블
        else if (baseTable.equals("t_abnormal_tracks")) {
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_time_idx ON %s.%s (time_bucket DESC)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_vessel_idx ON %s.%s (sig_src_cd, target_id)",
                    partitionName, schema, partitionName
            ));
            indexSqls.add(String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s_type_idx ON %s.%s (abnormal_type)",
                    partitionName, schema, partitionName
            ));
        }

        // 인덱스 생성 실행
        for (String indexSql : indexSqls) {
            try {
                jdbcTemplate.execute(indexSql);
                log.debug("Created index for partition {}", partitionName);
            } catch (Exception e) {
                log.warn("Failed to create index for partition {}: {}", partitionName, e.getMessage());
            }
        }
    }

    /**
     * 오래된 파티션 삭제
     */
    private void dropOldPartitions(LocalDate cutoffDate) {
        log.info("Dropping partitions older than {}", cutoffDate);

        // 수집 DB 파티션 조회
        List<PartitionInfo> collectPartitions = findPartitions("signal", "sig_test%", collectJdbcTemplate);

        // 조회 DB 파티션 조회
        List<PartitionInfo> queryPartitions = new ArrayList<>();

        // 일별 파티션 테이블
        for (String table : DAILY_PARTITION_TABLES) {
            if (!table.equals("sig_test")) {
                queryPartitions.addAll(findPartitions("signal", table + "_%", queryJdbcTemplate));
            }
        }

        // 월별 파티션 테이블 (월별은 보관 기간이 다를 수 있음)
        LocalDate monthlyCutoff = LocalDate.now().minusMonths(6); // 6개월 보관
        for (String table : MONTHLY_PARTITION_TABLES) {
            List<PartitionInfo> monthlyPartitions = findPartitions("signal", table + "_%", queryJdbcTemplate);
            monthlyPartitions.stream()
                    .filter(p -> p.partitionDate != null && p.partitionDate.isBefore(monthlyCutoff))
                    .forEach(p -> dropPartition(p, queryJdbcTemplate));
        }

        // 일별 파티션 삭제
        collectPartitions.stream()
                .filter(p -> p.partitionDate != null && p.partitionDate.isBefore(cutoffDate))
                .forEach(p -> dropPartition(p, collectJdbcTemplate));

        queryPartitions.stream()
                .filter(p -> p.partitionDate != null && p.partitionDate.isBefore(cutoffDate))
                .forEach(p -> dropPartition(p, queryJdbcTemplate));
    }

    /**
     * 파티션 정보 조회
     */
    private List<PartitionInfo> findPartitions(String schema, String tablePattern, JdbcTemplate jdbcTemplate) {
        String sql = """
            SELECT schemaname, tablename 
            FROM pg_tables 
            WHERE schemaname = ? AND tablename LIKE ?
            ORDER BY tablename
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            PartitionInfo info = new PartitionInfo();
            info.schema = rs.getString("schemaname");
            info.tableName = rs.getString("tablename");

            // 날짜 추출
            String tableName = info.tableName;

            // 일별 파티션 (테이블명_YYMMDD 형식)
            if (tableName.matches(".*_\\d{6}$")) {
                String dateStr = tableName.substring(tableName.lastIndexOf('_') + 1);
                try {
                    info.partitionDate = LocalDate.parse("20" + dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                } catch (Exception e) {
                    log.debug("Failed to parse daily date from partition name: {}", tableName);
                }
            }
            // 월별 파티션 (테이블명_YYYY_MM 형식)
            else if (tableName.matches(".*_\\d{4}_\\d{2}$")) {
                String yearMonth = tableName.substring(tableName.lastIndexOf('_', tableName.lastIndexOf('_') - 1) + 1);
                try {
                    info.partitionDate = LocalDate.parse(yearMonth + "_01", DateTimeFormatter.ofPattern("yyyy_MM_dd"));
                } catch (Exception e) {
                    log.debug("Failed to parse monthly date from partition name: {}", tableName);
                }
            }

            return info;
        }, schema, tablePattern);
    }

    /**
     * 파티션 삭제
     */
    private void dropPartition(PartitionInfo partition, JdbcTemplate jdbcTemplate) {
        try {
            // 삭제 전 데이터 건수 확인
            String countSql = String.format("SELECT COUNT(*) FROM %s.%s", partition.schema, partition.tableName);
            Integer rowCount = jdbcTemplate.queryForObject(countSql, Integer.class);

            // 파티션 삭제
            String dropSql = String.format("DROP TABLE IF EXISTS %s.%s", partition.schema, partition.tableName);
            jdbcTemplate.execute(dropSql);

            log.info("Dropped partition: {}.{} (contained {} rows)",
                    partition.schema, partition.tableName, rowCount);

        } catch (Exception e) {
            log.error("Failed to drop partition: {}.{}",
                    partition.schema, partition.tableName, e);
        }
    }

    /**
     * 파티션 통계 업데이트
     */
    private void updatePartitionStatistics() {
        try {
            // 주요 테이블만 ANALYZE 실행
            queryJdbcTemplate.execute("ANALYZE signal.t_vessel_tracks_5min");
            queryJdbcTemplate.execute("ANALYZE signal.t_vessel_tracks_hourly");
            queryJdbcTemplate.execute("ANALYZE signal.t_vessel_tracks_daily");
            queryJdbcTemplate.execute("ANALYZE signal.t_tile_summary");
            queryJdbcTemplate.execute("ANALYZE signal.t_area_statistics");

            log.info("Updated partition statistics");
        } catch (Exception e) {
            log.error("Failed to update partition statistics", e);
        }
    }

    /**
     * 파티션 상태 리포트
     */
    private void reportPartitionStatus() {
        try {
            // 현재 파티션 상태 조회
            String sql = """
                SELECT 
                    schemaname,
                    tablename,
                    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
                    (SELECT COUNT(*) FROM pg_indexes WHERE tablename = t.tablename) as index_count
                FROM pg_tables t
                WHERE schemaname = 'signal' 
                  AND tablename LIKE '%\\_%'
                  AND (tablename LIKE 'sig_test%' 
                       OR tablename LIKE 't_vessel_tracks%'
                       OR tablename LIKE 't_grid%'
                       OR tablename LIKE 't_area%'
                       OR tablename LIKE 't_tile%'
                       OR tablename LIKE 't_abnormal%')
                ORDER BY tablename
            """;

            log.info("=== Partition Status Report ===");

            // CollectDB 파티션
            log.info("--- CollectDB Partitions ---");
            List<Map<String, Object>> collectStatus = collectJdbcTemplate.queryForList(sql);
            collectStatus.stream()
                    .filter(row -> ((String)row.get("tablename")).startsWith("sig_test"))
                    .forEach(row -> {
                        log.info("  - {}: {} (indexes: {})",
                                row.get("tablename"),
                                row.get("size"),
                                row.get("index_count"));
                    });

            // QueryDB 파티션
            log.info("--- QueryDB Partitions ---");
            List<Map<String, Object>> queryStatus = queryJdbcTemplate.queryForList(sql);

            // 테이블별로 그룹화하여 출력
            log.info("  Daily Partitions:");
            queryStatus.stream()
                    .filter(row -> ((String)row.get("tablename")).matches(".*_\\d{6}$"))
                    .forEach(row -> {
                        log.info("    - {}: {} (indexes: {})",
                                row.get("tablename"),
                                row.get("size"),
                                row.get("index_count"));
                    });

            log.info("  Monthly Partitions:");
            queryStatus.stream()
                    .filter(row -> ((String)row.get("tablename")).matches(".*_\\d{4}_\\d{2}$"))
                    .forEach(row -> {
                        log.info("    - {}: {} (indexes: {})",
                                row.get("tablename"),
                                row.get("size"),
                                row.get("index_count"));
                    });

        } catch (Exception e) {
            log.error("Failed to report partition status", e);
        }
    }

    /**
     * 파티션 존재 확인
     */
    private boolean partitionExists(String schema, String partitionName, JdbcTemplate jdbcTemplate) {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schema, partitionName);

        if (!Boolean.TRUE.equals(exists)) {
            String dsInfo = DataSourceLogger.getDataSourceInfo(jdbcTemplate.getDataSource());
            log.debug("Partition {}.{} does not exist in: {}", schema, partitionName, dsInfo);
        }

        return Boolean.TRUE.equals(exists);
    }

    // 내부 클래스들
    private static class PartitionTask {
        String schema;
        String baseTable;
        LocalDate date;
        JdbcTemplate jdbcTemplate;
        String partitionType; // "daily" or "monthly"

        PartitionTask(String schema, String baseTable, LocalDate date, JdbcTemplate jdbcTemplate, String partitionType) {
            this.schema = schema;
            this.baseTable = baseTable;
            this.date = date;
            this.jdbcTemplate = jdbcTemplate;
            this.partitionType = partitionType;
        }
    }

    private static class PartitionInfo {
        String schema;
        String tableName;
        LocalDate partitionDate;
    }
}