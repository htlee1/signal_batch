package gc.mda.signal_batch.global.util;

import gc.mda.signal_batch.domain.vessel.model.VesselData;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Getter
@Setter
public class VesselTrackDataHolder {
    private List<VesselData> allVesselData = new ArrayList<>();
    
    public void clear() {
        allVesselData.clear();
    }
    
    public void setData(List<VesselData> data) {
        this.allVesselData = new ArrayList<>(data);
    }
    
    public int size() {
        return allVesselData.size();
    }
}
