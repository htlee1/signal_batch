package gc.mda.signal_batch.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin/batch")
@RequiredArgsConstructor
public class BatchAdminController {

    @Autowired
    @Qualifier("asyncJobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("vesselAggregationJob")
    private Job vesselAggregationJob;
    
    @Autowired
    @Qualifier("vesselTrackAggregationJob")
    private Job vesselTrackAggregationJob;

    @Autowired
    @Qualifier("dailyAggregationJob")
    private Job dailyAggregationJob;

    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;

    /**
     * Job 실행
     */
    @PostMapping("/job/run")
    public ResponseEntity<Map<String, Object>> runJob(
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        try {
            Job job;
            if ("dailyAggregationJob".equals(jobName)) {
                job = dailyAggregationJob;
            } else if ("vesselTrackAggregationJob".equals(jobName)) {
                job = vesselTrackAggregationJob;
            } else {
                job = vesselAggregationJob;
            }

            LocalDateTime start = startTime != null ?
                    LocalDateTime.parse(startTime) : LocalDateTime.now().minusHours(1);
            LocalDateTime end = endTime != null ?
                    LocalDateTime.parse(endTime) : LocalDateTime.now();

            JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                    .addString("startTime", start.withNano(0).toString())
                    .addString("endTime", end.withNano(0).toString())
                    .addLong("executionTime", System.currentTimeMillis());
            
            // vesselTrackAggregationJob의 경우 timeBucket 파라미터 추가
            if ("vesselTrackAggregationJob".equals(jobName)) {
                LocalDateTime timeBucket = start.withSecond(0).withNano(0)
                        .minusMinutes(start.getMinute() % 5);
                paramsBuilder.addString("timeBucket", timeBucket.toString());
            }
            
            JobParameters params = paramsBuilder.toJobParameters();

            JobExecution execution = jobLauncher.run(job, params);

            Map<String, Object> response = new HashMap<>();
            response.put("jobName", job.getName());
            response.put("executionId", execution.getId());
            response.put("status", execution.getStatus());
            response.put("startTime", start);
            response.put("endTime", end);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to run job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Job 중지
     */
    @PostMapping("/job/stop/{executionId}")
    public ResponseEntity<Map<String, Object>> stopJob(@PathVariable Long executionId) {
        try {
            boolean stopped = jobOperator.stop(executionId);

            return ResponseEntity.ok(Map.of(
                    "executionId", executionId,
                    "stopped", stopped,
                    "message", stopped ? "Job stop requested" : "Failed to stop job"
            ));
        } catch (Exception e) {
            log.error("Failed to stop job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Job 재시작
     */
    @PostMapping("/job/restart/{executionId}")
    public ResponseEntity<Map<String, Object>> restartJob(@PathVariable Long executionId) {
        try {
            Long newExecutionId = jobOperator.restart(executionId);

            return ResponseEntity.ok(Map.of(
                    "originalExecutionId", executionId,
                    "newExecutionId", newExecutionId,
                    "message", "Job restarted successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to restart job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 실행 중인 Job 목록
     */
    @GetMapping("/job/running")
    public ResponseEntity<List<Map<String, Object>>> getRunningJobs() {
        try {
            List<Map<String, Object>> runningJobs = new ArrayList<>();

            for (String jobName : jobExplorer.getJobNames()) {
                Set<JobExecution> executions = jobExplorer.findRunningJobExecutions(jobName);

                for (JobExecution execution : executions) {
                    Map<String, Object> jobInfo = new HashMap<>();
                    jobInfo.put("jobName", jobName);
                    jobInfo.put("executionId", execution.getId());
                    jobInfo.put("startTime", execution.getStartTime());
                    jobInfo.put("status", execution.getStatus());
                    jobInfo.put("parameters", execution.getJobParameters().getParameters());

                    // Step 정보
                    List<Map<String, Object>> stepInfos = new ArrayList<>();
                    for (StepExecution stepExecution : execution.getStepExecutions()) {
                        Map<String, Object> stepInfo = new HashMap<>();
                        stepInfo.put("stepName", stepExecution.getStepName());
                        stepInfo.put("status", stepExecution.getStatus());
                        stepInfo.put("readCount", stepExecution.getReadCount());
                        stepInfo.put("writeCount", stepExecution.getWriteCount());
                        stepInfo.put("skipCount", stepExecution.getSkipCount());
                        stepInfos.add(stepInfo);
                    }
                    jobInfo.put("steps", stepInfos);

                    runningJobs.add(jobInfo);
                }
            }

            return ResponseEntity.ok(runningJobs);
        } catch (Exception e) {
            log.error("Failed to get running jobs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }

    /**
     * Job 실행 이력
     */
    @GetMapping("/job/history")
    public ResponseEntity<List<Map<String, Object>>> getJobHistory(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "50") int limit) {

        try {
            List<Map<String, Object>> history = new ArrayList<>();

            if (jobName != null && !jobName.isEmpty()) {
                // 특정 Job만 조회
                List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, limit);
                addExecutionsToHistory(instances, history);
            } else {
                // 모든 Job 조회
                for (String name : jobExplorer.getJobNames()) {
                    List<JobInstance> instances = jobExplorer.getJobInstances(name, 0, limit / 3);
                    addExecutionsToHistory(instances, history);
                }
            }

            // 최신순 정렬
            history.sort((a, b) -> {
                Long idA = (Long) a.get("executionId");
                Long idB = (Long) b.get("executionId");
                return idB.compareTo(idA);
            });
            
            // limit 적용
            if (history.size() > limit) {
                history = history.subList(0, limit);
            }

            return ResponseEntity.ok(history);

        } catch (Exception e) {
            log.error("Failed to get job history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }
    
    private void addExecutionsToHistory(List<JobInstance> instances, List<Map<String, Object>> history) {
        for (JobInstance instance : instances) {
            List<JobExecution> executions = jobExplorer.getJobExecutions(instance);

            for (JobExecution execution : executions) {
                Map<String, Object> executionInfo = new HashMap<>();
                executionInfo.put("jobName", instance.getJobName());
                executionInfo.put("executionId", execution.getId());
                executionInfo.put("startTime", execution.getStartTime());
                executionInfo.put("endTime", execution.getEndTime());
                executionInfo.put("status", execution.getStatus());
                executionInfo.put("exitCode", execution.getExitStatus().getExitCode());
                executionInfo.put("exitDescription", execution.getExitStatus().getExitDescription());

                // Duration 계산
                if (execution.getStartTime() != null && execution.getEndTime() != null) {
                    long duration = java.time.Duration.between(
                            execution.getStartTime(),
                            execution.getEndTime()
                    ).getSeconds();
                    executionInfo.put("durationSeconds", duration);
                }

                // 처리 통계
                long totalRead = execution.getStepExecutions().stream()
                        .mapToLong(StepExecution::getReadCount).sum();
                long totalWrite = execution.getStepExecutions().stream()
                        .mapToLong(StepExecution::getWriteCount).sum();
                long totalSkip = execution.getStepExecutions().stream()
                        .mapToLong(StepExecution::getSkipCount).sum();

                executionInfo.put("totalRead", totalRead);
                executionInfo.put("totalWrite", totalWrite);
                executionInfo.put("totalSkip", totalSkip);

                history.add(executionInfo);
            }
        }
    }

    /**
     * Step 실행 상세
     */
    @GetMapping("/step/details/{executionId}")
    public ResponseEntity<List<Map<String, Object>>> getStepDetails(@PathVariable Long executionId) {
        try {
            JobExecution jobExecution = jobExplorer.getJobExecution(executionId);
            if (jobExecution == null) {
                return ResponseEntity.notFound().build();
            }

            List<Map<String, Object>> stepDetails = new ArrayList<>();

            for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("stepName", stepExecution.getStepName());
                detail.put("status", stepExecution.getStatus());
                detail.put("startTime", stepExecution.getStartTime());
                detail.put("endTime", stepExecution.getEndTime());
                detail.put("readCount", stepExecution.getReadCount());
                detail.put("writeCount", stepExecution.getWriteCount());
                detail.put("filterCount", stepExecution.getFilterCount());
                detail.put("skipCount", stepExecution.getSkipCount());
                detail.put("commitCount", stepExecution.getCommitCount());
                detail.put("rollbackCount", stepExecution.getRollbackCount());

                // Duration
                if (stepExecution.getStartTime() != null && stepExecution.getEndTime() != null) {
                    long duration = java.time.Duration.between(
                            stepExecution.getStartTime(),
                            stepExecution.getEndTime()
                    ).getSeconds();
                    detail.put("durationSeconds", duration);
                }

                // 에러 정보
                if (!stepExecution.getFailureExceptions().isEmpty()) {
                    List<String> errors = stepExecution.getFailureExceptions().stream()
                            .map(Throwable::getMessage)
                            .collect(Collectors.toList());
                    detail.put("errors", errors);
                }

                stepDetails.add(detail);
            }

            return ResponseEntity.ok(stepDetails);

        } catch (Exception e) {
            log.error("Failed to get step details", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }

    /**
     * 실패한 Job 목록
     */
    @GetMapping("/job/failed")
    public ResponseEntity<List<Map<String, Object>>> getFailedJobs(
            @RequestParam(defaultValue = "24") int hoursBack) {

        try {
            LocalDateTime since = LocalDateTime.now().minusHours(hoursBack);
            List<Map<String, Object>> failedJobs = new ArrayList<>();

            for (String jobName : jobExplorer.getJobNames()) {
                List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 100);

                for (JobInstance instance : instances) {
                    List<JobExecution> executions = jobExplorer.getJobExecutions(instance);

                    for (JobExecution execution : executions) {
                        if (execution.getStatus() == BatchStatus.FAILED &&
                                execution.getStartTime() != null &&
                                execution.getStartTime().isAfter(since)) {

                            Map<String, Object> failedJob = new HashMap<>();
                            failedJob.put("jobName", jobName);
                            failedJob.put("executionId", execution.getId());
                            failedJob.put("startTime", execution.getStartTime());
                            failedJob.put("endTime", execution.getEndTime());
                            failedJob.put("exitCode", execution.getExitStatus().getExitCode());
                            failedJob.put("exitDescription", execution.getExitStatus().getExitDescription());

                            // 실패 원인
                            List<String> failureReasons = new ArrayList<>();
                            for (Throwable t : execution.getAllFailureExceptions()) {
                                failureReasons.add(t.getMessage());
                            }
                            failedJob.put("failureReasons", failureReasons);

                            failedJobs.add(failedJob);
                        }
                    }
                }
            }

            return ResponseEntity.ok(failedJobs);

        } catch (Exception e) {
            log.error("Failed to get failed jobs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }

    /**
     * 배치 통계
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getBatchStatistics(
            @RequestParam(defaultValue = "7") int days) {

        try {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            Map<String, Object> statistics = new HashMap<>();

            int totalExecutions = 0;
            int successfulExecutions = 0;
            int failedExecutions = 0;
            long totalRecordsProcessed = 0;
            long totalDuration = 0;

            Map<String, Integer> jobExecutionCounts = new HashMap<>();
            Map<String, Long> jobProcessingTimes = new HashMap<>();

            for (String jobName : jobExplorer.getJobNames()) {
                List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 1000);

                for (JobInstance instance : instances) {
                    List<JobExecution> executions = jobExplorer.getJobExecutions(instance);

                    for (JobExecution execution : executions) {
                        if (execution.getStartTime() != null &&
                                execution.getStartTime().isAfter(since)) {

                            totalExecutions++;

                            if (execution.getStatus() == BatchStatus.COMPLETED) {
                                successfulExecutions++;
                            } else if (execution.getStatus() == BatchStatus.FAILED) {
                                failedExecutions++;
                            }

                            // 처리 레코드 수
                            long records = execution.getStepExecutions().stream()
                                    .mapToLong(StepExecution::getReadCount)
                                    .sum();
                            totalRecordsProcessed += records;

                            // 실행 시간
                            if (execution.getEndTime() != null) {
                                long duration = java.time.Duration.between(
                                        execution.getStartTime(),
                                        execution.getEndTime()
                                ).getSeconds();
                                totalDuration += duration;

                                jobProcessingTimes.merge(jobName, duration, Long::sum);
                            }

                            jobExecutionCounts.merge(jobName, 1, Integer::sum);
                        }
                    }
                }
            }

            statistics.put("period", Map.of(
                    "start", since,
                    "end", LocalDateTime.now(),
                    "days", days
            ));

            statistics.put("summary", Map.of(
                    "totalExecutions", totalExecutions,
                    "successful", successfulExecutions,
                    "failed", failedExecutions,
                    "successRate", totalExecutions > 0 ?
                            (double) successfulExecutions / totalExecutions * 100 : 0,
                    "totalRecordsProcessed", totalRecordsProcessed,
                    "avgRecordsPerExecution", totalExecutions > 0 ?
                            totalRecordsProcessed / totalExecutions : 0,
                    "totalProcessingTimeSeconds", totalDuration,
                    "avgProcessingTimeSeconds", totalExecutions > 0 ?
                            totalDuration / totalExecutions : 0
            ));

            statistics.put("byJob", Map.of(
                    "executionCounts", jobExecutionCounts,
                    "processingTimes", jobProcessingTimes
            ));

            return ResponseEntity.ok(statistics);

        } catch (Exception e) {
            log.error("Failed to get batch statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 일별 처리 통계 (Dashboard 차트용)
     */
    @GetMapping("/daily-stats")
    public ResponseEntity<Map<String, Object>> getDailyStatistics() {
        try {
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> dailyStats = new ArrayList<>();
            
            // 최근 7일간 일별 처리량
            for (int i = 6; i >= 0; i--) {
                LocalDateTime date = LocalDateTime.now().minusDays(i).withHour(0).withMinute(0).withSecond(0);
                LocalDateTime nextDate = date.plusDays(1);
                
                long totalProcessed = 0;
                int vesselJobCount = 0;
                int trackJobCount = 0;
                
                // 모든 Job의 실행 이력 확인
                for (String jobName : jobExplorer.getJobNames()) {
                    List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 1000);
                    
                    for (JobInstance instance : instances) {
                        List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                        
                        for (JobExecution execution : executions) {
                            if (execution.getStartTime() != null &&
                                execution.getStartTime().isAfter(date) &&
                                execution.getStartTime().isBefore(nextDate) &&
                                execution.getStatus() == BatchStatus.COMPLETED) {
                                
                                // 처리된 레코드 수 계산
                                long records = execution.getStepExecutions().stream()
                                        .mapToLong(StepExecution::getWriteCount)
                                        .sum();
                                totalProcessed += records;
                                
                                // Job 타입별 카운트
                                if (jobName.contains("vesselAggregation")) {
                                    vesselJobCount++;
                                } else if (jobName.contains("vesselTrack")) {
                                    trackJobCount++;
                                }
                            }
                        }
                    }
                }
                
                Map<String, Object> dailyStat = new HashMap<>();
                dailyStat.put("date", date.toLocalDate().toString());
                dailyStat.put("totalProcessed", totalProcessed);
                dailyStat.put("vesselJobs", vesselJobCount);
                dailyStat.put("trackJobs", trackJobCount);
                dailyStats.add(dailyStat);
            }
            
            // Job 상태별 요약 (Status Distribution Chart용)
            Map<String, Integer> statusSummary = new HashMap<>();
            statusSummary.put("completed", 0);
            statusSummary.put("failed", 0);
            statusSummary.put("stopped", 0);
            
            // 최근 24시간 Job 상태 통계
            LocalDateTime since24h = LocalDateTime.now().minusHours(24);
            for (String jobName : jobExplorer.getJobNames()) {
                List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 200);
                
                for (JobInstance instance : instances) {
                    List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                    
                    for (JobExecution execution : executions) {
                        if (execution.getStartTime() != null &&
                            execution.getStartTime().isAfter(since24h)) {
                            
                            String status = execution.getStatus().toString().toLowerCase();
                            statusSummary.merge(status, 1, Integer::sum);
                        }
                    }
                }
            }
            
            result.put("dailyStats", dailyStats);
            result.put("statusSummary", statusSummary);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Failed to get daily statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Helper 메소드
    private List<JobInstance> getAllJobInstances(int limit) {
        List<JobInstance> allInstances = new ArrayList<>();

        for (String jobName : jobExplorer.getJobNames()) {
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, limit);
            allInstances.addAll(instances);
        }

        return allInstances.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }
}