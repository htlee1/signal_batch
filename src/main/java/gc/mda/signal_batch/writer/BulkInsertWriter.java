//package gc.mda.signal_batch.writer;
//
//import gc.mda.signal_batch.model.TileStatistics;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.batch.core.configuration.annotation.StepScope;
//import org.springframework.batch.item.ItemWriter;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import java.util.List;
//
//@Slf4j
//@Configuration
//@RequiredArgsConstructor
//public class BulkInsertWriter {
//
//    @Autowired
//    private OptimizedBulkInsertWriter optimizedBulkInsertWriter;
//
//    @Bean
//    @StepScope
//    public ItemWriter<List<TileStatistics>> tileStatisticsBulkWriter() {
//        return optimizedBulkInsertWriter.tileStatisticsBulkWriter();
//    }
//}