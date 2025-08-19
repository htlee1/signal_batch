package gc.mda.signal_batch.monitoring.metric;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class BulkInsertMetrics {

    private final MeterRegistry meterRegistry;

    public void recordBulkInsert(String table, long records, long duration) {
        // 처리량 기록
        meterRegistry.counter("bulk.insert.records",
                "table", table).increment(records);

        // 소요 시간 기록
        meterRegistry.timer("bulk.insert.duration",
                "table", table).record(duration, TimeUnit.MILLISECONDS);

        // 처리 속도 (records/sec)
        double throughput = records * 1000.0 / duration;
        meterRegistry.gauge("bulk.insert.throughput",
                Tags.of("table", table), throughput);
    }
}