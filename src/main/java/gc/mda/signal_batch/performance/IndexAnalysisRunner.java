package gc.mda.signal_batch.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 인덱스 분석 실행기
 * 애플리케이션 시작 시 --index.analyze=true 옵션으로 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "index.analyze", havingValue = "true")
public class IndexAnalysisRunner implements CommandLineRunner {

    private final DatabaseIndexOptimizer indexOptimizer;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== 데이터베이스 인덱스 분석 시작 ===");
        
        try {
            // 인덱스 분석 실행
            DatabaseIndexOptimizer.IndexAnalysisReport report = indexOptimizer.analyzeIndexes();
            
            // 1. 현재 인덱스 상태
            log.info("\n### 1. 현재 인덱스 상태 ###");
            log.info("전체 인덱스 수: {}", report.getIndexStatistics().getTotalIndexCount());
            log.info("전체 인덱스 크기: {}", report.getIndexStatistics().getTotalIndexSize());
            
            log.info("\n가장 많이 사용되는 인덱스 TOP 5:");
            report.getIndexStatistics().getTopUsedIndexes().forEach(idx -> 
                log.info("  - {}: {} scans", idx.get("indexname"), idx.get("idx_scan"))
            );
            
            log.info("\n가장 큰 인덱스 TOP 5:");
            report.getIndexStatistics().getLargestIndexes().forEach(idx -> 
                log.info("  - {}: {}", idx.get("indexname"), idx.get("size"))
            );
            
            // 2. 누락된 인덱스
            log.info("\n### 2. 누락된 인덱스 (권장사항) ###");
            List<DatabaseIndexOptimizer.MissingIndexSuggestion> missingIndexes = report.getMissingIndexes();
            if (missingIndexes.isEmpty()) {
                log.info("누락된 인덱스가 없습니다.");
            } else {
                for (DatabaseIndexOptimizer.MissingIndexSuggestion missing : missingIndexes) {
                    log.info("\n테이블: {}", missing.getTableName());
                    log.info("  컬럼: {}", missing.getColumns());
                    log.info("  이유: {}", missing.getReason());
                    log.info("  우선순위: {} (1-5, 5가 가장 높음)", missing.getPriority());
                }
            }
            
            // 3. 사용하지 않는 인덱스
            log.info("\n### 3. 사용하지 않는 인덱스 ###");
            List<DatabaseIndexOptimizer.UnusedIndex> unusedIndexes = report.getUnusedIndexes();
            if (unusedIndexes.isEmpty()) {
                log.info("사용하지 않는 인덱스가 없습니다.");
            } else {
                for (DatabaseIndexOptimizer.UnusedIndex unused : unusedIndexes) {
                    log.info("\n인덱스: {}", unused.getIndexName());
                    log.info("  테이블: {}", unused.getTableName());
                    log.info("  스캔 횟수: {} (100회 미만)", unused.getScanCount());
                    log.info("  크기: {}", unused.getIndexSize());
                }
            }
            
            // 4. 인덱스 생성/삭제 SQL
            log.info("\n### 4. 인덱스 최적화 SQL ###");
            
            // 생성 SQL
            if (!missingIndexes.isEmpty()) {
                log.info("\n--- 인덱스 생성 SQL ---");
                List<String> createSQLs = indexOptimizer.generateIndexCreationSQL(missingIndexes);
                createSQLs.forEach(sql -> log.info(sql));
            }
            
            // 삭제 SQL
            if (!unusedIndexes.isEmpty()) {
                log.info("\n--- 인덱스 삭제 SQL (주의: 신중히 검토 후 실행) ---");
                unusedIndexes.forEach(unused -> {
                    String dropSQL = String.format("DROP INDEX CONCURRENTLY IF EXISTS signal.%s;", 
                                                  unused.getIndexName());
                    log.info(dropSQL);
                });
            }
            
            // 5. 추가 권장사항
            log.info("\n### 5. 추가 권장사항 ###");
            List<String> recommendations = report.getRecommendations();
            if (recommendations.isEmpty()) {
                log.info("추가 권장사항이 없습니다.");
            } else {
                recommendations.forEach(rec -> log.info("- {}", rec));
            }
            
            log.info("\n=== 인덱스 분석 완료 ===");
            
        } catch (Exception e) {
            log.error("인덱스 분석 중 오류 발생", e);
        }
    }
}
