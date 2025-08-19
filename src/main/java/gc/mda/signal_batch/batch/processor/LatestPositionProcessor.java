package gc.mda.signal_batch.batch.processor;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import gc.mda.signal_batch.domain.vessel.model.VesselLatestPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LatestPositionProcessor {

    @StepScope
    public ItemProcessor<VesselData, VesselLatestPosition> processor() {
        // 청크 내에서 최신 데이터만 유지
        ConcurrentHashMap<String, VesselLatestPosition> latestMap = new ConcurrentHashMap<>();

        return item -> {
            if (!item.isValidPosition()) {
                log.debug("Invalid position for vessel: {}", item.getVesselKey());
                return null;
            }

            String key = item.getVesselKey();
            VesselLatestPosition current = VesselLatestPosition.fromVesselData(item);

            VesselLatestPosition existing = latestMap.get(key);
            if (existing == null || current.getLastUpdate().isAfter(existing.getLastUpdate())) {
                latestMap.put(key, current);
                return current;
            }

            return null;
        };
    }

    @StepScope
    public ItemProcessor<VesselData, VesselLatestPosition> filteringProcessor(
            LocalDateTime cutoffTime) {

        return item -> {
            // 특정 시간 이후 데이터만 처리
            if (item.getMessageTime().isBefore(cutoffTime)) {
                return null;
            }

            if (!item.isValidPosition()) {
                return null;
            }

            return VesselLatestPosition.fromVesselData(item);
        };
    }
}