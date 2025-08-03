package gc.mda.signal_batch.writer;

import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.processor.AbnormalTrackDetector.AbnormalDetectionResult;
import gc.mda.signal_batch.processor.AbnormalTrackDetector.AbnormalSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 정상/비정상 궤적을 분리하여 저장하는 복합 Writer
 */
@Slf4j
@RequiredArgsConstructor
public class CompositeTrackWriter implements ItemWriter<AbnormalDetectionResult> {
    
    private final VesselTrackBulkWriter vesselTrackBulkWriter;
    private final AbnormalTrackWriter abnormalTrackWriter;
    private final String targetTable;
    
    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        // Job 이름을 AbnormalTrackWriter에 전달
        String jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
        abnormalTrackWriter.setJobName(jobName);
        log.debug("CompositeTrackWriter: Job name = {}", jobName);
    }
    
    @Override
    public void write(Chunk<? extends AbnormalDetectionResult> chunk) throws Exception {
        List<VesselTrack> normalTracks = new ArrayList<>();
        List<AbnormalDetectionResult> abnormalResults = new ArrayList<>();
        
        for (AbnormalDetectionResult result : chunk) {
            if (result.hasAbnormalities()) {
                // 비정상 궤적 수집
                abnormalResults.add(result);
                
                // 정정된 궤적이 있으면 정상 궤적으로 저장
                // null이면 전체 궤적이 비정상이므로 제외
                if (result.getCorrectedTrack() != null) {
                    normalTracks.add(result.getCorrectedTrack());
                } else {
                    log.debug("비정상 궤적 전체 제외: vessel={}", 
                        result.getOriginalTrack().getVesselKey());
                }
            } else {
                // 정상 궤적
                normalTracks.add(result.getOriginalTrack());
            }
        }
        
        // 정상 궤적 저장
        if (!normalTracks.isEmpty()) {
            if ("hourly".equals(targetTable)) {
                vesselTrackBulkWriter.writeHourlyTracks(normalTracks);
            } else if ("daily".equals(targetTable)) {
                vesselTrackBulkWriter.writeDailyTracks(normalTracks);
            } else {
                throw new IllegalArgumentException("Unknown target table: " + targetTable);
            }
            log.info("Wrote {} normal tracks to {} table", normalTracks.size(), targetTable);
        }
        
        // 비정상 궤적 저장
        if (!abnormalResults.isEmpty()) {
            abnormalTrackWriter.write(new Chunk<>(abnormalResults));
            log.info("Wrote {} abnormal track results", abnormalResults.size());
        }
    }
}
