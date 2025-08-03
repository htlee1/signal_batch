package gc.mda.signal_batch.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionedReader {

    @Qualifier("collectJdbcTemplate")
    private final JdbcTemplate collectJdbcTemplate;

    @StepScope
    public Partitioner dayPartitioner(@Value("#{jobParameters['processingDate']}") LocalDate processingDate) {
        return gridSize -> {
            Map<String, ExecutionContext> partitions = new HashMap<>();

            // 파티션 존재 확인
            String partitionName = generatePartitionName(processingDate);

            if (checkPartitionExists(partitionName)) {
                // 시간대별로 파티션 생성 (gridSize 고려)
                int hoursPerPartition = 24 / Math.min(gridSize, 24);
                int actualPartitions = Math.min(gridSize, 24);

                for (int i = 0; i < actualPartitions; i++) {
                    ExecutionContext context = new ExecutionContext();

                    int startHour = i * hoursPerPartition;
                    int endHour = (i == actualPartitions - 1) ? 24 : (i + 1) * hoursPerPartition;

                    context.put("partition", partitionName);
                    context.put("startTime", processingDate.atTime(startHour, 0));
                    context.put("endTime", processingDate.atTime(endHour, 0));
                    context.put("partitionIndex", i);

                    partitions.put("partition-" + i, context);
                }

                log.info("Created {} partitions for table {}", partitions.size(), partitionName);

            } else {
                // 파티션이 없는 경우 처리
                log.warn("Partition {} does not exist. Creating fallback partition.", partitionName);

                // 동적으로 파티션 생성 시도
                if (createMissingPartition(processingDate)) {
                    // 재귀 호출로 다시 파티셔닝
                    return dayPartitioner(processingDate).partition(gridSize);
                }

                // 실패 시 단일 파티션으로 처리
                ExecutionContext context = new ExecutionContext();
                context.put("partition", "");  // 전체 테이블에서 날짜 조건으로 읽기
                context.put("startTime", processingDate.atStartOfDay());
                context.put("endTime", processingDate.plusDays(1).atStartOfDay());
                context.put("partitionIndex", 0);
                partitions.put("partition-fallback", context);
            }

            return partitions;
        };
    }

    /**
     * 시간 범위 기반 파티셔너
     */
    @StepScope
    public Partitioner rangePartitioner(
            @Value("#{jobParameters['startTime']}") LocalDateTime startTime,
            @Value("#{jobParameters['endTime']}") LocalDateTime endTime,
            @Value("#{jobParameters['partitionCount']}") Integer partitionCount) {

        return gridSize -> {
            Map<String, ExecutionContext> partitions = new HashMap<>();

            // 날짜별로 그룹화
            Map<LocalDate, List<LocalDateTime>> dateGroups = groupByDate(startTime, endTime);

            int partitionIndex = 0;
            for (Map.Entry<LocalDate, List<LocalDateTime>> entry : dateGroups.entrySet()) {
                LocalDate date = entry.getKey();
                String partitionName = findPartitionForDate(date);

                // 각 날짜에 대해 시간 범위 분할
                LocalDateTime dayStart = entry.getValue().get(0);
                LocalDateTime dayEnd = entry.getValue().get(1);

                long totalMinutes = java.time.Duration.between(dayStart, dayEnd).toMinutes();
                int subPartitions = Math.max(1, (int)(totalMinutes / 60)); // 시간 단위로 분할

                for (int i = 0; i < subPartitions; i++) {
                    ExecutionContext context = new ExecutionContext();

                    LocalDateTime partStart = dayStart.plusHours(i);
                    LocalDateTime partEnd = (i == subPartitions - 1) ? dayEnd : dayStart.plusHours(i + 1);

                    context.put("startTime", partStart);
                    context.put("endTime", partEnd);
                    context.put("partition", partitionName != null ? partitionName : "");
                    context.put("partitionIndex", partitionIndex++);

                    partitions.put("range-partition-" + partitionIndex, context);
                }
            }

            log.info("Created {} range partitions for period {} to {}",
                    partitions.size(), startTime, endTime);

            return partitions;
        };
    }

    private String generatePartitionName(LocalDate date) {
        // YYMMDD 형식으로 변경
        return "sig_test_" + date.format(DateTimeFormatter.ofPattern("yyMMdd"));
    }

    private boolean checkPartitionExists(String partitionName) {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'signal' AND tablename = ?)";
        return Boolean.TRUE.equals(collectJdbcTemplate.queryForObject(sql, Boolean.class, partitionName));
    }

    private String findPartitionForDate(LocalDate date) {
        String partitionName = generatePartitionName(date);
        return checkPartitionExists(partitionName) ? partitionName : null;
    }

    private boolean createMissingPartition(LocalDate date) {
        try {
            String partitionName = generatePartitionName(date);
            String sql = String.format("""
                CREATE TABLE IF NOT EXISTS signal.%s PARTITION OF signal.sig_test
                FOR VALUES FROM ('%s') TO ('%s')
                """, partitionName, date, date.plusDays(1));

            collectJdbcTemplate.execute(sql);
            log.info("Successfully created missing partition: {}", partitionName);
            return true;

        } catch (Exception e) {
            log.error("Failed to create missing partition for date: {}", date, e);
            return false;
        }
    }

    private Map<LocalDate, List<LocalDateTime>> groupByDate(LocalDateTime start, LocalDateTime end) {
        Map<LocalDate, List<LocalDateTime>> groups = new HashMap<>();

        LocalDate currentDate = start.toLocalDate();
        while (!currentDate.isAfter(end.toLocalDate())) {
            LocalDateTime dayStart = currentDate.equals(start.toLocalDate()) ?
                    start : currentDate.atStartOfDay();
            LocalDateTime dayEnd = currentDate.equals(end.toLocalDate()) ?
                    end : currentDate.plusDays(1).atStartOfDay();

            groups.put(currentDate, List.of(dayStart, dayEnd));
            currentDate = currentDate.plusDays(1);
        }

        return groups;
    }
}