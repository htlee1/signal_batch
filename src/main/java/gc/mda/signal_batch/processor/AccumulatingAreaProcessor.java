package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.processor.AreaStatisticsProcessor.AreaStatistics;
import gc.mda.signal_batch.processor.AreaStatisticsProcessor.VesselMovement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Area Statistics를 위한 누적 프로세서
 * 전체 데이터를 메모리에 누적한 후 Step 종료 시 한 번에 집계
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class AccumulatingAreaProcessor implements ItemProcessor<VesselData, AreaStatistics> {

    private final AreaStatisticsProcessor areaStatisticsProcessor;

    @Value("#{jobParameters['timeBucketMinutes']}")
    private Integer timeBucketMinutes;

    // area_id + time_bucket별 선박 데이터 누적
    private final Map<String, List<VesselData>> dataAccumulator = new ConcurrentHashMap<>();

    // 처리 통계
    private long processedCount = 0;
    private long skippedCount = 0;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        int bucketMinutes = (timeBucketMinutes != null) ? timeBucketMinutes : 5;
        log.info("AccumulatingAreaProcessor initialized with timeBucket: {} minutes", bucketMinutes);
        dataAccumulator.clear();
        processedCount = 0;
        skippedCount = 0;
    }

    @Override
    public AreaStatistics process(VesselData item) throws Exception {
        if (!item.isValidPosition()) {
            skippedCount++;
            return null;
        }

        // 메모리에서 속한 구역 찾기
        List<String> areaIds = areaStatisticsProcessor.findAreasForPointInMemory(
                item.getLat(), item.getLon()
        );

        if (areaIds.isEmpty()) {
            return null;
        }

        // time bucket 계산
        int bucketSize = timeBucketMinutes != null ? timeBucketMinutes : 5;
        LocalDateTime bucket = item.getMessageTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .withMinute((item.getMessageTime().getMinute() / bucketSize) * bucketSize);

        // 각 area에 대해 데이터 누적
        for (String areaId : areaIds) {
            String key = areaId + "||" + bucket.toString();  // 구분자 변경
            dataAccumulator.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        processedCount++;

        // null 반환으로 개별 출력 방지
        return null;
    }

    @AfterStep
    public void afterStep(StepExecution stepExecution) {
        log.info("Processing accumulated data for {} area-timebucket combinations",
                dataAccumulator.size());
        log.info("Processed: {}, Skipped: {}", processedCount, skippedCount);

        if (dataAccumulator.isEmpty()) {
            return;
        }

        // 누적된 데이터를 기반으로 통계 계산
        List<AreaStatistics> allStatistics = new ArrayList<>();

        dataAccumulator.forEach((key, vessels) -> {
            String[] parts = key.split("\\|\\|", 2);  // || 구분자 사용
            if (parts.length != 2) {
                log.error("Invalid key format: {}", key);
                return;
            }
            String areaId = parts[0];
            LocalDateTime timeBucket = LocalDateTime.parse(parts[1]);

            AreaStatistics stats = new AreaStatistics(areaId, timeBucket);
            Map<String, VesselMovement> vesselMovements = new HashMap<>();

            // 각 선박별로 movement 정보 계산
            Map<String, List<VesselData>> vesselGroups = new HashMap<>();
            for (VesselData vessel : vessels) {
                vesselGroups.computeIfAbsent(vessel.getVesselKey(), k -> new ArrayList<>())
                        .add(vessel);
            }

            vesselGroups.forEach((vesselKey, vesselDataList) -> {
                // 시간순 정렬
                vesselDataList.sort(Comparator.comparing(VesselData::getMessageTime));

                VesselMovement movement = new VesselMovement();
                movement.setVesselKey(vesselKey);
                movement.setEnterTime(vesselDataList.get(0).getMessageTime());
                movement.setExitTime(vesselDataList.get(vesselDataList.size() - 1).getMessageTime());
                movement.setPointCount(vesselDataList.size());

                // 평균 속도 계산
                double totalSpeed = 0;
                int speedCount = 0;
                for (VesselData vd : vesselDataList) {
                    if (vd.getSog() != null) {
                        totalSpeed += vd.getSog().doubleValue();
                        speedCount++;
                    }
                }

                if (speedCount > 0) {
                    movement.setAvgSpeed(BigDecimal.valueOf(totalSpeed / speedCount)
                            .setScale(2, BigDecimal.ROUND_HALF_UP));
                } else {
                    movement.setAvgSpeed(BigDecimal.ZERO);
                }

                // 정류/통과 구분 (10분 이상 체류 시 정류)
                long stayMinutes = ChronoUnit.MINUTES.between(
                        movement.getEnterTime(), movement.getExitTime()
                );

                if (stayMinutes > 10) {
                    stats.getStationaryVessels().put(vesselKey, movement);
                } else {
                    stats.getTransitVessels().put(vesselKey, movement);
                }

                vesselMovements.put(vesselKey, movement);
            });

            // 통계 최종 계산
            stats.setVesselCount(vesselMovements.size());
            stats.setInCount(vesselMovements.size()); // 진입 선박 수
            stats.setOutCount(0); // 추후 로직 개선 필요

            // 전체 평균 속도
            List<BigDecimal> allSpeeds = new ArrayList<>();
            vesselMovements.values().stream()
                    .map(VesselMovement::getAvgSpeed)
                    .filter(Objects::nonNull)
                    .forEach(allSpeeds::add);

            if (!allSpeeds.isEmpty()) {
                BigDecimal totalSpeed = allSpeeds.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                stats.setAvgSog(totalSpeed.divide(
                        BigDecimal.valueOf(allSpeeds.size()), 2, BigDecimal.ROUND_HALF_UP));
            } else {
                stats.setAvgSog(BigDecimal.ZERO);
            }

            allStatistics.add(stats);
        });

        // StepExecution context에 결과 저장
        stepExecution.getExecutionContext().put("areaStatistics", allStatistics);
        log.info("Calculated statistics for {} areas", allStatistics.size());
    }
}