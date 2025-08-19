package gc.mda.signal_batch.batch.reader;

import gc.mda.signal_batch.global.util.VesselDataHolder;
import gc.mda.signal_batch.domain.vessel.model.VesselData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;


@Component
@RequiredArgsConstructor
@Slf4j
public class InMemoryVesselDataReader implements ItemReader<VesselData> {
    
    private final VesselDataHolder dataHolder;
    private Iterator<VesselData> iterator;
    private boolean initialized = false;
    
    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        List<VesselData> data = dataHolder.getData();
        this.iterator = data.iterator();
        this.initialized = true;
        log.info("Initialized reader with {} items for step: {}", 
                data.size(), stepExecution.getStepName());
    }
    
    @Override
    public VesselData read() {
        if (!initialized) {
            throw new IllegalStateException("Reader not initialized");
        }
        
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }
    
    @AfterStep
    public void afterStep(StepExecution stepExecution) {
        iterator = null;
        initialized = false;
    }
}