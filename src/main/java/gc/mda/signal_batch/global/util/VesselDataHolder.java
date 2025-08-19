package gc.mda.signal_batch.global.util;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class VesselDataHolder {
    private List<VesselData> latestPositions;
    private LocalDateTime loadTime;
    
    public synchronized void setData(List<VesselData> data) {
        this.latestPositions = new ArrayList<>(data);
        this.loadTime = LocalDateTime.now();
        log.info("Loaded {} vessel positions into memory", data.size());
    }
    
    public synchronized List<VesselData> getData() {
        return latestPositions != null ? new ArrayList<>(latestPositions) : new ArrayList<>();
    }
    
    public boolean isDataStale(int maxAgeMinutes) {
        return loadTime == null || 
               loadTime.isBefore(LocalDateTime.now().minusMinutes(maxAgeMinutes));
    }
    
    public void clear() {
        latestPositions = null;
        loadTime = null;
    }
    
    public synchronized int size() {
        return latestPositions != null ? latestPositions.size() : 0;
    }

}