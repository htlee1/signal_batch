package gc.mda.signal_batch.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class MetricsController {

    private final JobExplorer jobExplorer;
    private final JdbcTemplate queryJdbcTemplate;


    @GetMapping("/metrics/summary")
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new HashMap<>();

        try {
            // Job 통계
            Set<JobExecution> allExecutions = jobExplorer.findRunningJobExecutions("vesselAggregationJob");

            summary.put("totalJobsCompleted", allExecutions.size());
            summary.put("totalRecordsProcessed", 0);
            summary.put("averageThroughput", 0);
            summary.put("errorRate", 0.0);

            // 메모리 정보
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            // Memory 정보를 별도 객체로 추가
            Map<String, Object> memory = new HashMap<>();
            memory.put("used", usedMemory / 1024 / 1024);  // MB 단위
            memory.put("total", totalMemory / 1024 / 1024);
            memory.put("max", runtime.maxMemory() / 1024 / 1024);
            summary.put("memory", memory);

            // Thread 정보
            summary.put("threads", Thread.activeCount());

            // Processing 정보 추가
            Map<String, Object> processing = new HashMap<>();
            processing.put("recordsPerSecond", Math.random() * 1000); // 실제 구현 필요
            summary.put("processing", processing);

            // Database 정보
            Map<String, Object> database = new HashMap<>();
            try {
                Integer activeConnections = queryJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'active'",
                        Integer.class
                );
                database.put("activeConnections", activeConnections != null ? activeConnections : 0);
            } catch (Exception e) {
                database.put("activeConnections", 0);
            }
            summary.put("database", database);

        } catch (Exception e) {
            log.error("Failed to get metrics summary", e);
        }

        return summary;
    }

    @GetMapping("/jobs/recent")
    public List<Map<String, Object>> getRecentJobs(@RequestParam(defaultValue = "10") int count) {
        List<Map<String, Object>> jobs = new ArrayList<>();

        try {
            // 최근 JobInstance 조회
            List<JobInstance> instances = jobExplorer.getJobInstances("vesselAggregationJob", 0, count);

            for (JobInstance instance : instances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instance);

                for (JobExecution execution : executions) {
                    Map<String, Object> job = new HashMap<>();
                    job.put("jobName", instance.getJobName());
                    job.put("status", execution.getStatus().toString());
                    job.put("startTime", execution.getStartTime());
                    job.put("endTime", execution.getEndTime());

                    if (execution.getStartTime() != null && execution.getEndTime() != null) {
                        long duration = Duration.between(
                                execution.getStartTime().toInstant(ZoneOffset.UTC),
                                execution.getEndTime().toInstant(ZoneOffset.UTC)
                        ).getSeconds();
                        job.put("duration", duration);
                    } else {
                        job.put("duration", 0);
                    }

                    long recordsProcessed = execution.getStepExecutions().stream()
                            .mapToLong(s -> s.getWriteCount())
                            .sum();
                    job.put("recordsProcessed", recordsProcessed);

                    jobs.add(job);
                }
            }

        } catch (Exception e) {
            log.error("Failed to get recent jobs", e);
        }

        return jobs;
    }

    @GetMapping("/haegu/stats")
    public List<Map<String, Object>> getHaeguStats() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // 먼저 최신 데이터 시간 확인
            Map<String, Object> latestData = queryJdbcTemplate.queryForMap(
                    "SELECT MAX(time_bucket) as latest_time FROM signal.t_tile_summary"
            );

            log.info("Latest data time: {}", latestData.get("latest_time"));

            // t_grid_tiles를 활용한 개선된 쿼리
            String sql = """
                WITH latest_bucket AS (
                    SELECT MAX(time_bucket) as max_time
                    FROM signal.t_tile_summary
                    WHERE time_bucket > NOW() - INTERVAL '15 minutes'
                ),
                haegu_stats AS (
                    SELECT 
                        t.tile_id,
                        t.tile_level,
                        t.vessel_count,
                        t.vessel_density,
                        g.haegu_no,
                        g.sohaegu_no,
                        g.tile_geom
                    FROM signal.t_tile_summary t
                    JOIN signal.t_grid_tiles g ON t.tile_id = g.tile_id AND t.tile_level = g.tile_level
                    WHERE t.time_bucket = (SELECT max_time FROM latest_bucket)
                ),
                haegu_aggregated AS (
                    SELECT 
                        haegu_no,
                        -- 대해구 레벨 (tile_level = 0) 데이터
                        MAX(CASE WHEN tile_level = 0 THEN vessel_count ELSE 0 END) as vessel_count_main,
                        MAX(CASE WHEN tile_level = 0 THEN vessel_density ELSE 0 END) as density_main,
                        -- 소해구 레벨 (tile_level = 1) 데이터 합계
                        SUM(CASE WHEN tile_level = 1 THEN vessel_count ELSE 0 END) as vessel_count_sub,
                        -- 대해구의 기하 정보 (중심점 계산용)
                        MAX(CASE WHEN tile_level = 0 THEN ST_X(ST_Centroid(tile_geom))::numeric(10,6) END) as center_lon,
                        MAX(CASE WHEN tile_level = 0 THEN ST_Y(ST_Centroid(tile_geom))::numeric(10,6) END) as center_lat
                    FROM haegu_stats
                    GROUP BY haegu_no
                )
                SELECT 
                    haegu_no,
                    CONCAT('대해구 ', haegu_no) as haegu_name,
                    -- 대해구 레벨 데이터가 있으면 사용, 없으면 소해구 합계 사용
                    CASE 
                        WHEN vessel_count_main > 0 THEN vessel_count_main
                        ELSE vessel_count_sub
                    END as total_vessels,
                    -- vessel_count를 unique_vessels로 사용
                    CASE 
                        WHEN vessel_count_main > 0 THEN vessel_count_main
                        ELSE vessel_count_sub
                    END as unique_vessels,
                    COALESCE(density_main, 0) as avg_density,
                    -- 검증용: 대해구와 소해구 합계 차이
                    ABS(vessel_count_main - vessel_count_sub) as discrepancy,
                    center_lat,
                    center_lon
                FROM haegu_aggregated
                WHERE vessel_count_main > 0 OR vessel_count_sub > 0
                ORDER BY total_vessels DESC
                LIMIT 50
            """;

            results = queryJdbcTemplate.queryForList(sql);

            // 결과가 없으면 더 넓은 시간 범위로 재시도
            if (results.isEmpty()) {
                log.info("No data within 10 minutes, trying 1 hour range");
                sql = sql.replace("10 minutes", "1 hour");
                results = queryJdbcTemplate.queryForList(sql);
            }

            log.info("Haegu stats result count: {}", results.size());

        } catch (Exception e) {
            log.error("Failed to get haegu stats", e);
            // 빈 결과 반환
        }

        return results;
    }

    /**
     * 대해구 데이터 정합성 검증
     */
    @GetMapping("/haegu/validate")
    public Map<String, Object> validateHaeguData() {
        Map<String, Object> validation = new HashMap<>();

        try {
            // 1. tile_level 분포 확인
            List<Map<String, Object>> levelDistribution = queryJdbcTemplate.queryForList(
                    """
                    SELECT 
                        tile_level,
                        COUNT(*) as tile_count,
                        COUNT(DISTINCT tile_id) as unique_tiles
                    FROM signal.t_tile_summary
                    WHERE time_bucket > NOW() - INTERVAL '1 hour'
                    GROUP BY tile_level
                    ORDER BY tile_level
                    """
            );
            validation.put("tileLevelDistribution", levelDistribution);

            // 2. 중복 데이터 확인
            List<Map<String, Object>> duplicates = queryJdbcTemplate.queryForList(
                    """
                    SELECT 
                        tile_id,
                        time_bucket,
                        COUNT(*) as duplicate_count
                    FROM signal.t_tile_summary
                    WHERE time_bucket > NOW() - INTERVAL '1 hour'
                    GROUP BY tile_id, time_bucket
                    HAVING COUNT(*) > 1
                    ORDER BY duplicate_count DESC
                    LIMIT 20
                    """
            );
            validation.put("duplicateRecords", duplicates);

            // 3. 대해구-소해구 집계 검증
            List<Map<String, Object>> aggregationCheck = queryJdbcTemplate.queryForList(
                    """
                    WITH latest_bucket AS (
                        SELECT MAX(time_bucket) as max_time
                        FROM signal.t_tile_summary
                        WHERE time_bucket > NOW() - INTERVAL '1 hour'
                    )
                    SELECT 
                        haegu_no,
                        -- 대해구 레벨 vessel_count
                        COALESCE(MAX(CASE WHEN tile_level = 0 THEN vessel_count END), 0) as main_vessel_count,
                        -- 소해구 레벨 vessel_count 합계
                        COALESCE(SUM(CASE WHEN tile_level = 1 THEN vessel_count ELSE 0 END), 0) as sub_vessel_count_sum,
                        -- 차이
                        ABS(COALESCE(MAX(CASE WHEN tile_level = 0 THEN vessel_count END), 0) - 
                            COALESCE(SUM(CASE WHEN tile_level = 1 THEN vessel_count ELSE 0 END), 0)) as difference,
                        -- 차이 비율
                        CASE 
                            WHEN MAX(CASE WHEN tile_level = 0 THEN vessel_count END) > 0 
                            THEN ROUND((ABS(COALESCE(MAX(CASE WHEN tile_level = 0 THEN vessel_count END), 0) - 
                                           COALESCE(SUM(CASE WHEN tile_level = 1 THEN vessel_count ELSE 0 END), 0))::NUMERIC / 
                                       MAX(CASE WHEN tile_level = 0 THEN vessel_count END) * 100), 2)
                            ELSE 0
                        END as difference_percent
                    FROM signal.t_tile_summary t
                    JOIN signal.t_grid_tiles g ON t.tile_id = g.tile_id AND t.tile_level = g.tile_level
                    WHERE t.time_bucket = (SELECT max_time FROM latest_bucket)
                    GROUP BY g.haegu_no
                    HAVING MAX(CASE WHEN tile_level = 0 THEN vessel_count END) > 0 OR 
                           SUM(CASE WHEN tile_level = 1 THEN vessel_count ELSE 0 END) > 0
                    ORDER BY difference_percent DESC
                    LIMIT 20
                    """
            );
            validation.put("haeguAggregationCheck", aggregationCheck);

        } catch (Exception e) {
            log.error("Failed to validate haegu data", e);
            validation.put("error", e.getMessage());
        }

        return validation;
    }
}