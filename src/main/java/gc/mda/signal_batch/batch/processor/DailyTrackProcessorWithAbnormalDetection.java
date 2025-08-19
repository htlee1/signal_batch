package gc.mda.signal_batch.batch.processor;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * 일별 궤적 프로세서 - 비정상 궤적 검출 기능 포함
 */
@Slf4j
public class DailyTrackProcessorWithAbnormalDetection extends BaseTrackProcessorWithAbnormalDetection {
    
    public DailyTrackProcessorWithAbnormalDetection(
            ItemProcessor<VesselTrack.VesselKey, VesselTrack> dailyTrackProcessor,
            AbnormalTrackDetector abnormalTrackDetector,
            DataSource queryDataSource) {
        super(dailyTrackProcessor, abnormalTrackDetector, queryDataSource);
    }
    
    @Override
    protected String getPreviousTrackTableName() {
        return "signal.t_vessel_tracks_hourly";
    }
    
    @Override
    protected LocalDateTime getNormalizedBucket(LocalDateTime timeBucket) {
        return timeBucket.withHour(0).withMinute(0).withSecond(0).withNano(0);
    }
    
    @Override
    protected LocalDateTime getPreviousBucket(LocalDateTime currentBucket) {
        return currentBucket.minusDays(1);
    }
}
