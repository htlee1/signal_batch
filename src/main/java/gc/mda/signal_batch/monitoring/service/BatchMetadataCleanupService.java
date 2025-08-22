package gc.mda.signal_batch.monitoring.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Spring Batch 메타데이터 자동 정리 서비스
 * - 오래된 배치 실행 이력 및 ExecutionContext 데이터 정리
 * - 패키지 변경으로 인한 역직렬화 오류 데이터 정리
 * - 디스크 공간 확보 및 성능 개선
 */
@Slf4j
@Service
public class BatchMetadataCleanupService {

    private final JdbcTemplate jdbcTemplate;

    public BatchMetadataCleanupService(@Qualifier("queryJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Value("${batch.metadata.cleanup.retention-days:30}")
    private int retentionDays;

    @Value("${batch.metadata.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${batch.metadata.cleanup.dry-run:false}")
    private boolean dryRun;

    /**
     * 매주 일요일 새벽 2시에 메타데이터 정리 실행
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    @Transactional
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            log.info("Batch metadata cleanup is disabled");
            return;
        }

        log.info("========== Scheduled Batch Metadata Cleanup Started ==========");
        performCleanup();
        log.info("========== Scheduled Batch Metadata Cleanup Completed ==========");
    }

    /**
     * 수동 정리 실행 (Admin API용)
     */
    @Transactional
    public CleanupResult performCleanup() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        log.info("Starting batch metadata cleanup - retention period: {} days, cutoff date: {}", 
                retentionDays, cutoffDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        CleanupResult result = new CleanupResult();
        
        try {
            // 1. 현재 상태 조회
            result.beforeStepExecutionContextCount = getTableCount("batch_step_execution_context");
            result.beforeJobExecutionContextCount = getTableCount("batch_job_execution_context");
            result.beforeStepExecutionCount = getTableCount("batch_step_execution");
            result.beforeJobExecutionCount = getTableCount("batch_job_execution");
            result.beforeJobInstanceCount = getTableCount("batch_job_instance");

            log.info("Before cleanup - StepExecutionContext: {}, JobExecutionContext: {}, StepExecution: {}, JobExecution: {}, JobInstance: {}",
                    result.beforeStepExecutionContextCount, result.beforeJobExecutionContextCount,
                    result.beforeStepExecutionCount, result.beforeJobExecutionCount, result.beforeJobInstanceCount);

            // 2. 정리 대상 조회
            String targetJobExecutionsQuery = """
                SELECT COUNT(*) FROM batch_job_execution 
                WHERE create_time < ? AND status IN ('COMPLETED', 'FAILED', 'STOPPED')
                """;
            
            int targetJobExecutions = jdbcTemplate.queryForObject(targetJobExecutionsQuery, Integer.class, cutoffDate);
            log.info("Target job executions for cleanup: {}", targetJobExecutions);

            if (targetJobExecutions == 0) {
                log.info("No old batch metadata found for cleanup");
                return result;
            }

            // 3. 정리 실행
            if (dryRun) {
                log.info("DRY RUN MODE - No actual cleanup performed");
            } else {
                result.deletedStepExecutionContextCount = cleanupStepExecutionContext(cutoffDate);
                result.deletedJobExecutionContextCount = cleanupJobExecutionContext(cutoffDate);
                result.deletedStepExecutionCount = cleanupStepExecution(cutoffDate);
                result.deletedJobExecutionCount = cleanupJobExecution(cutoffDate);
                result.deletedJobInstanceCount = cleanupJobInstance(cutoffDate);
            }

            // 4. 정리 후 상태 조회
            result.afterStepExecutionContextCount = getTableCount("batch_step_execution_context");
            result.afterJobExecutionContextCount = getTableCount("batch_job_execution_context");
            result.afterStepExecutionCount = getTableCount("batch_step_execution");
            result.afterJobExecutionCount = getTableCount("batch_job_execution");
            result.afterJobInstanceCount = getTableCount("batch_job_instance");

            log.info("After cleanup - StepExecutionContext: {}, JobExecutionContext: {}, StepExecution: {}, JobExecution: {}, JobInstance: {}",
                    result.afterStepExecutionContextCount, result.afterJobExecutionContextCount,
                    result.afterStepExecutionCount, result.afterJobExecutionCount, result.afterJobInstanceCount);

            // 5. 결과 요약
            log.info("Cleanup completed - Deleted: StepExecutionContext={}, JobExecutionContext={}, StepExecution={}, JobExecution={}, JobInstance={}",
                    result.deletedStepExecutionContextCount, result.deletedJobExecutionContextCount,
                    result.deletedStepExecutionCount, result.deletedJobExecutionCount, result.deletedJobInstanceCount);

            result.success = true;

        } catch (Exception e) {
            log.error("Failed to cleanup batch metadata", e);
            result.success = false;
            result.errorMessage = e.getMessage();
        }

        return result;
    }

    /**
     * 손상된 ExecutionContext 데이터 정리 (패키지 변경으로 인한 역직렬화 오류)
     */
    @Transactional
    public CleanupResult cleanupCorruptedExecutionContext() {
        log.info("Starting cleanup of corrupted ExecutionContext data");
        
        CleanupResult result = new CleanupResult();
        
        try {
            // 패키지 변경 이전 데이터 (특정 날짜 이전) 삭제
            String corruptedDataCutoff = "2024-08-01 00:00:00";
            
            String cleanupQuery = """
                DELETE FROM batch_step_execution_context 
                WHERE step_execution_id IN (
                    SELECT se.step_execution_id 
                    FROM batch_step_execution se
                    JOIN batch_job_execution je ON se.job_execution_id = je.job_execution_id
                    WHERE je.create_time < ?
                )
                """;
            
            if (!dryRun) {
                result.deletedStepExecutionContextCount = jdbcTemplate.update(cleanupQuery, corruptedDataCutoff);
            }
            
            log.info("Cleaned up {} corrupted ExecutionContext records", result.deletedStepExecutionContextCount);
            result.success = true;
            
        } catch (Exception e) {
            log.error("Failed to cleanup corrupted ExecutionContext data", e);
            result.success = false;
            result.errorMessage = e.getMessage();
        }
        
        return result;
    }

    private int cleanupStepExecutionContext(LocalDateTime cutoffDate) {
        String query = """
            DELETE FROM batch_step_execution_context 
            WHERE step_execution_id IN (
                SELECT se.step_execution_id 
                FROM batch_step_execution se
                JOIN batch_job_execution je ON se.job_execution_id = je.job_execution_id
                WHERE je.create_time < ? AND je.status IN ('COMPLETED', 'FAILED', 'STOPPED')
            )
            """;
        return jdbcTemplate.update(query, cutoffDate);
    }

    private int cleanupJobExecutionContext(LocalDateTime cutoffDate) {
        String query = """
            DELETE FROM batch_job_execution_context 
            WHERE job_execution_id IN (
                SELECT job_execution_id 
                FROM batch_job_execution 
                WHERE create_time < ? AND status IN ('COMPLETED', 'FAILED', 'STOPPED')
            )
            """;
        return jdbcTemplate.update(query, cutoffDate);
    }

    private int cleanupStepExecution(LocalDateTime cutoffDate) {
        String query = """
            DELETE FROM batch_step_execution 
            WHERE job_execution_id IN (
                SELECT job_execution_id 
                FROM batch_job_execution 
                WHERE create_time < ? AND status IN ('COMPLETED', 'FAILED', 'STOPPED')
            )
            """;
        return jdbcTemplate.update(query, cutoffDate);
    }

    private int cleanupJobExecution(LocalDateTime cutoffDate) {
        String query = """
            DELETE FROM batch_job_execution 
            WHERE create_time < ? AND status IN ('COMPLETED', 'FAILED', 'STOPPED')
            """;
        return jdbcTemplate.update(query, cutoffDate);
    }

    private int cleanupJobInstance(LocalDateTime cutoffDate) {
        String query = """
            DELETE FROM batch_job_instance 
            WHERE job_instance_id NOT IN (
                SELECT DISTINCT job_instance_id 
                FROM batch_job_execution
            )
            """;
        return jdbcTemplate.update(query);
    }

    private int getTableCount(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    /**
     * 정리 작업 결과
     */
    public static class CleanupResult {
        public boolean success;
        public String errorMessage;
        
        // 정리 전 상태
        public int beforeStepExecutionContextCount;
        public int beforeJobExecutionContextCount;
        public int beforeStepExecutionCount;
        public int beforeJobExecutionCount;
        public int beforeJobInstanceCount;
        
        // 삭제된 레코드 수
        public int deletedStepExecutionContextCount;
        public int deletedJobExecutionContextCount;
        public int deletedStepExecutionCount;
        public int deletedJobExecutionCount;
        public int deletedJobInstanceCount;
        
        // 정리 후 상태
        public int afterStepExecutionContextCount;
        public int afterJobExecutionContextCount;
        public int afterStepExecutionCount;
        public int afterJobExecutionCount;
        public int afterJobInstanceCount;
    }
}