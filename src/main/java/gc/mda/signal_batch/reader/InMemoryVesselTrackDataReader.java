package gc.mda.signal_batch.reader;

import gc.mda.signal_batch.common.VesselTrackDataHolder;
import gc.mda.signal_batch.model.VesselData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemReader;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class InMemoryVesselTrackDataReader implements ItemReader<List<VesselData>> {
    
    private final VesselTrackDataHolder dataHolder;
    private final int chunkSize;
    
    private Iterator<Map.Entry<String, List<VesselData>>> groupIterator;
    private List<List<VesselData>> currentChunk;
    private Iterator<List<VesselData>> chunkIterator;
    private boolean initialized = false;
    
    public void initialize() {
        
        // 선박별로 그룹화 (sig_src_cd + target_id)
        Map<String, List<VesselData>> groupedData = dataHolder.getAllVesselData().stream()
                .collect(Collectors.groupingBy(VesselData::getVesselKey));
        
        // 각 그룹 내에서 시간순 정렬
        groupedData.forEach((key, dataList) -> 
                dataList.sort(Comparator.comparing(VesselData::getMessageTime)));
        
        groupIterator = groupedData.entrySet().iterator();
        currentChunk = new ArrayList<>();
        
        log.info("Initialized track reader with {} vessel groups", groupedData.size());
    }
    
    @Override
    public List<VesselData> read() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        
        // 현재 청크에서 데이터 반환
        if (chunkIterator != null && chunkIterator.hasNext()) {
            return chunkIterator.next();
        }
        
        // 새로운 청크 생성
        currentChunk.clear();
        int count = 0;
        
        while (groupIterator.hasNext() && count < chunkSize) {
            Map.Entry<String, List<VesselData>> entry = groupIterator.next();
            currentChunk.add(entry.getValue());
            count++;
        }
        
        if (currentChunk.isEmpty()) {
            return null; // 더 이상 데이터 없음
        }
        
        chunkIterator = currentChunk.iterator();
        return chunkIterator.next();
    }
    

}
