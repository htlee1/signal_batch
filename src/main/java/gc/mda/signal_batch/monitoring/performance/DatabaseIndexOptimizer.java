package gc.mda.signal_batch.monitoring.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;


/**
 * 데이터베이스 인덱스 분석 및 최적화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseIndexOptimizer {

    @SuppressWarnings("unused")
    private final JdbcTemplate collectJdbcTemplate;
    private final JdbcTemplate queryJdbcTemplate;

    /**
     * 인덱스 분석 실행
     */
    public IndexAnalysisReport analyzeIndexes() {
        IndexAnalysisReport report = new IndexAnalysisReport();
        
        // 현재 인덱스 조회
        report.setExistingIndexes(getExistingIndexes());
        
        // 누락된 인덱스 감지
        report.setMissingIndexes(detectMissingIndexes());
        
        // 사용되지 않는 인덱스 감지
        report.setUnusedIndexes(detectUnusedIndexes());
        
        // 인덱스 통계 분석
        report.setIndexStatistics(analyzeIndexStatistics());
        
        // 인덱스 권장사항 생성
        report.setRecommendations(generateIndexRecommendations());
        
        return report;
    }

    /**
     * 기존 인덱스 조회
     */
    private List<IndexInfo> getExistingIndexes() {
        String sql = """
            SELECT 
                schemaname,
                tablename,
                indexname,
                indexdef,
                pg_size_pretty(pg_relation_size(indexrelid)) as index_size,
                idx_scan,
                idx_tup_read,
                idx_tup_fetch
            FROM pg_stat_user_indexes
            JOIN pg_indexes USING (schemaname, tablename, indexname)
            WHERE schemaname = 'signal'
            ORDER BY schemaname, tablename, indexname
        """;
        
        return queryJdbcTemplate.query(sql, (rs, rowNum) -> {
            IndexInfo info = new IndexInfo();
            info.setSchemaName(rs.getString("schemaname"));
            info.setTableName(rs.getString("tablename"));
            info.setIndexName(rs.getString("indexname"));
            info.setIndexDefinition(rs.getString("indexdef"));
            info.setIndexSize(rs.getString("index_size"));
            info.setScanCount(rs.getLong("idx_scan"));
            info.setTuplesRead(rs.getLong("idx_tup_read"));
            info.setTuplesFetched(rs.getLong("idx_tup_fetch"));
            return info;
        });
    }

    /**
     * 누락된 인덱스 감지
     */
    private List<MissingIndexSuggestion> detectMissingIndexes() {
        List<MissingIndexSuggestion> suggestions = new ArrayList<>();
        
        // 주요 테이블별 권장 인덱스 확인
        Map<String, List<String>> recommendedIndexes = Map.of(
            "sig_test", Arrays.asList(
                "(target_id, message_time DESC)",
                "(message_time)",
                "(sig_src_cd, message_time)",
                "(lat, lon)"
            ),
            "t_vessel_tracks_5min", Arrays.asList(
                "(time_bucket, sig_src_cd, target_id)",
                "(sig_src_cd, target_id, time_bucket DESC)",
                "USING GIST (track_geom)"
            ),
            "t_grid_vessel_tracks", Arrays.asList(
                "(time_bucket, haegu_no)",
                "(haegu_no, time_bucket DESC)",
                "USING GIST (track_geom)"
            ),
            "t_area_vessel_tracks", Arrays.asList(
                "(time_bucket, area_id)",
                "(area_id, time_bucket DESC)",
                "USING GIST (track_geom)"
            )
        );
        
        for (Map.Entry<String, List<String>> entry : recommendedIndexes.entrySet()) {
            String tableName = entry.getKey();
            List<String> recommendedCols = entry.getValue();
            
            // 현재 테이블의 인덱스 확인
            List<String> existingIndexCols = getExistingIndexColumns(tableName);
            
            for (String cols : recommendedCols) {
                if (!hasMatchingIndex(existingIndexCols, cols)) {
                    MissingIndexSuggestion suggestion = new MissingIndexSuggestion();
                    suggestion.setTableName(tableName);
                    suggestion.setColumns(cols);
                    suggestion.setReason(determineIndexReason(tableName, cols));
                    suggestion.setPriority(calculateIndexPriority(tableName, cols));
                    suggestions.add(suggestion);
                }
            }
        }
        
        return suggestions;
    }

    /**
     * 테이블의 기존 인덱스 컬럼 조회
     */
    private List<String> getExistingIndexColumns(String tableName) {
        String sql = """
            SELECT pg_get_indexdef(indexrelid) as indexdef
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal' AND tablename = ?
        """;
        
        return queryJdbcTemplate.queryForList(sql, String.class, tableName);
    }

    /**
     * 매칭되는 인덱스 존재 여부 확인
     */
    private boolean hasMatchingIndex(List<String> existingIndexes, String columns) {
        String normalizedCols = columns.toLowerCase().replaceAll("\\s+", "");
        return existingIndexes.stream()
                .anyMatch(idx -> idx.toLowerCase().contains(normalizedCols));
    }

    /**
     * 인덱스 필요 이유 판단
     */
    private String determineIndexReason(String tableName, String columns) {
        if (columns.contains("time_bucket")) {
            return "Time-based queries optimization";
        } else if (columns.contains("GIST")) {
            return "Spatial queries optimization";
        } else if (columns.contains("target_id")) {
            return "Vessel lookup optimization";
        } else if (columns.contains("haegu_no") || columns.contains("area_id")) {
            return "Area-based filtering optimization";
        }
        return "General query performance improvement";
    }

    /**
     * 인덱스 우선순위 계산
     */
    private int calculateIndexPriority(String tableName, String columns) {
        // 시간 기반 인덱스가 가장 중요
        if (columns.contains("time_bucket") || columns.contains("message_time")) {
            return 5;
        }
        // 공간 인덱스
        else if (columns.contains("GIST")) {
            return 4;
        }
        // 주요 조회 키
        else if (columns.contains("target_id")) {
            return 3;
        }
        // 필터링용 인덱스
        else if (columns.contains("haegu_no") || columns.contains("area_id")) {
            return 2;
        }
        return 1;
    }

    /**
     * 사용되지 않는 인덱스 감지
     */
    private List<UnusedIndex> detectUnusedIndexes() {
        String sql = """
            SELECT 
                schemaname,
                tablename,
                indexname,
                idx_scan,
                pg_size_pretty(pg_relation_size(indexrelid)) as index_size
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal'
                AND idx_scan < 100
                AND indexrelname NOT LIKE '%_pkey'
            ORDER BY pg_relation_size(indexrelid) DESC
        """;
        
        return queryJdbcTemplate.query(sql, (rs, rowNum) -> {
            UnusedIndex index = new UnusedIndex();
            index.setSchemaName(rs.getString("schemaname"));
            index.setTableName(rs.getString("tablename"));
            index.setIndexName(rs.getString("indexname"));
            index.setScanCount(rs.getLong("idx_scan"));
            index.setIndexSize(rs.getString("index_size"));
            return index;
        });
    }

    /**
     * 인덱스 통계 분석
     */
    private IndexStatistics analyzeIndexStatistics() {
        IndexStatistics stats = new IndexStatistics();
        
        // 전체 인덱스 수
        String countSql = "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'signal'";
        stats.setTotalIndexCount(queryJdbcTemplate.queryForObject(countSql, Integer.class));
        
        // 전체 인덱스 크기
        String sizeSql = """
            SELECT pg_size_pretty(SUM(pg_relation_size(indexrelid))) 
            FROM pg_stat_user_indexes 
            WHERE schemaname = 'signal'
        """;
        stats.setTotalIndexSize(queryJdbcTemplate.queryForObject(sizeSql, String.class));
        
        // 가장 많이 사용되는 인덱스
        String topUsedSql = """
            SELECT indexname, idx_scan
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal'
            ORDER BY idx_scan DESC
            LIMIT 5
        """;
        stats.setTopUsedIndexes(queryJdbcTemplate.queryForList(topUsedSql));
        
        // 가장 큰 인덱스
        String largestSql = """
            SELECT indexname, pg_size_pretty(pg_relation_size(indexrelid)) as size
            FROM pg_stat_user_indexes
            WHERE schemaname = 'signal'
            ORDER BY pg_relation_size(indexrelid) DESC
            LIMIT 5
        """;
        stats.setLargestIndexes(queryJdbcTemplate.queryForList(largestSql));
        
        return stats;
    }

    /**
     * 인덱스 권장사항 생성
     */
    private List<String> generateIndexRecommendations() {
        List<String> recommendations = new ArrayList<>();
        
        // 테이블별 Sequential Scan 비율 확인
        String seqScanSql = """
            SELECT 
                schemaname,
                tablename,
                seq_scan,
                idx_scan,
                CASE WHEN seq_scan + idx_scan > 0 
                    THEN round(100.0 * seq_scan / (seq_scan + idx_scan), 2)
                    ELSE 0 
                END as seq_scan_ratio
            FROM pg_stat_user_tables
            WHERE schemaname = 'signal'
                AND seq_scan + idx_scan > 1000
            ORDER BY seq_scan_ratio DESC
        """;
        
        List<Map<String, Object>> seqScanResults = queryJdbcTemplate.queryForList(seqScanSql);
        
        for (Map<String, Object> row : seqScanResults) {
            double seqScanRatio = ((Number) row.get("seq_scan_ratio")).doubleValue();
            if (seqScanRatio > 50) {
                String tableName = (String) row.get("tablename");
                recommendations.add(String.format(
                    "Table '%s' has %.2f%% sequential scan ratio. Consider adding appropriate indexes.",
                    tableName, seqScanRatio
                ));
            }
        }
        
        // 인덱스 팽창 확인
        String bloatSql = """
            SELECT 
                tablename,
                iname as indexname,
                pg_size_pretty(wastedbytes) as wasted
            FROM (
                SELECT
                    current_database() AS dbname,
                    schemaname,
                    tablename,
                    indexname AS iname,
                    bs*(relpages-est_pages_ff) AS wastedbytes
                FROM (
                    SELECT
                        schemaname,
                        tablename,
                        indexname,
                        bs,
                        relpages,
                        CEIL((reltuples*(datahdr-12))/(bs-20::float)) AS est_pages_ff
                    FROM (
                        SELECT
                            schemaname,
                            tablename,
                            indexname,
                            reltuples,
                            relpages,
                            current_setting('block_size')::numeric AS bs,
                            CASE WHEN version() ~ 'mingw32' OR version() ~ '64-bit|x86_64|ppc64|ia64|amd64' 
                                THEN 8 ELSE 4 END AS ma,
                            24 AS datahdr
                        FROM pg_stat_user_indexes
                        JOIN pg_class ON pg_stat_user_indexes.indexrelid = pg_class.oid
                        WHERE schemaname = 'signal'
                    ) AS s1
                ) AS s2
                WHERE relpages > est_pages_ff
            ) AS s3
            WHERE wastedbytes > 1048576
            ORDER BY wastedbytes DESC
        """;
        
        try {
            List<Map<String, Object>> bloatResults = queryJdbcTemplate.queryForList(bloatSql);
            for (Map<String, Object> row : bloatResults) {
                recommendations.add(String.format(
                    "Index '%s' on table '%s' has %s of wasted space. Consider REINDEX.",
                    row.get("indexname"), row.get("tablename"), row.get("wasted")
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to check index bloat: {}", e.getMessage());
        }
        
        return recommendations;
    }

    /**
     * 인덱스 생성 SQL 생성
     */
    public List<String> generateIndexCreationSQL(List<MissingIndexSuggestion> missingIndexes) {
        List<String> sqlStatements = new ArrayList<>();
        
        for (MissingIndexSuggestion suggestion : missingIndexes) {
            String indexName = generateIndexName(suggestion.getTableName(), suggestion.getColumns());
            String sql;
            
            if (suggestion.getColumns().contains("GIST")) {
                sql = String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON signal.%s %s;",
                    indexName, suggestion.getTableName(), suggestion.getColumns()
                );
            } else {
                sql = String.format(
                    "CREATE INDEX CONCURRENTLY IF NOT EXISTS %s ON signal.%s %s;",
                    indexName, suggestion.getTableName(), suggestion.getColumns()
                );
            }
            
            sqlStatements.add(sql);
        }
        
        return sqlStatements;
    }

    /**
     * 인덱스 이름 생성
     */
    private String generateIndexName(String tableName, String columns) {
        String cleanColumns = columns.replaceAll("[\\s(),]", "_")
                                   .replaceAll("_+", "_")
                                   .toLowerCase();
        return String.format("idx_%s_%s", tableName, cleanColumns);
    }

    // 내부 클래스들
    public static class IndexAnalysisReport {
        private List<IndexInfo> existingIndexes;
        private List<MissingIndexSuggestion> missingIndexes;
        private List<UnusedIndex> unusedIndexes;
        private IndexStatistics indexStatistics;
        private List<String> recommendations;

        // Getter/Setter
        public List<IndexInfo> getExistingIndexes() { return existingIndexes; }
        public void setExistingIndexes(List<IndexInfo> existingIndexes) { this.existingIndexes = existingIndexes; }
        public List<MissingIndexSuggestion> getMissingIndexes() { return missingIndexes; }
        public void setMissingIndexes(List<MissingIndexSuggestion> missingIndexes) { this.missingIndexes = missingIndexes; }
        public List<UnusedIndex> getUnusedIndexes() { return unusedIndexes; }
        public void setUnusedIndexes(List<UnusedIndex> unusedIndexes) { this.unusedIndexes = unusedIndexes; }
        public IndexStatistics getIndexStatistics() { return indexStatistics; }
        public void setIndexStatistics(IndexStatistics indexStatistics) { this.indexStatistics = indexStatistics; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
    }

    public static class IndexInfo {
        private String schemaName;
        private String tableName;
        private String indexName;
        private String indexDefinition;
        private String indexSize;
        private long scanCount;
        private long tuplesRead;
        private long tuplesFetched;

        // Getter/Setter
        public String getSchemaName() { return schemaName; }
        public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
        public String getIndexDefinition() { return indexDefinition; }
        public void setIndexDefinition(String indexDefinition) { this.indexDefinition = indexDefinition; }
        public String getIndexSize() { return indexSize; }
        public void setIndexSize(String indexSize) { this.indexSize = indexSize; }
        public long getScanCount() { return scanCount; }
        public void setScanCount(long scanCount) { this.scanCount = scanCount; }
        public long getTuplesRead() { return tuplesRead; }
        public void setTuplesRead(long tuplesRead) { this.tuplesRead = tuplesRead; }
        public long getTuplesFetched() { return tuplesFetched; }
        public void setTuplesFetched(long tuplesFetched) { this.tuplesFetched = tuplesFetched; }
    }

    public static class MissingIndexSuggestion {
        private String tableName;
        private String columns;
        private String reason;
        private int priority;

        // Getter/Setter
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getColumns() { return columns; }
        public void setColumns(String columns) { this.columns = columns; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }

    public static class UnusedIndex {
        private String schemaName;
        private String tableName;
        private String indexName;
        private long scanCount;
        private String indexSize;

        // Getter/Setter
        public String getSchemaName() { return schemaName; }
        public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
        public long getScanCount() { return scanCount; }
        public void setScanCount(long scanCount) { this.scanCount = scanCount; }
        public String getIndexSize() { return indexSize; }
        public void setIndexSize(String indexSize) { this.indexSize = indexSize; }
    }

    public static class IndexStatistics {
        private int totalIndexCount;
        private String totalIndexSize;
        private List<Map<String, Object>> topUsedIndexes;
        private List<Map<String, Object>> largestIndexes;

        // Getter/Setter
        public int getTotalIndexCount() { return totalIndexCount; }
        public void setTotalIndexCount(int totalIndexCount) { this.totalIndexCount = totalIndexCount; }
        public String getTotalIndexSize() { return totalIndexSize; }
        public void setTotalIndexSize(String totalIndexSize) { this.totalIndexSize = totalIndexSize; }
        public List<Map<String, Object>> getTopUsedIndexes() { return topUsedIndexes; }
        public void setTopUsedIndexes(List<Map<String, Object>> topUsedIndexes) { this.topUsedIndexes = topUsedIndexes; }
        public List<Map<String, Object>> getLargestIndexes() { return largestIndexes; }
        public void setLargestIndexes(List<Map<String, Object>> largestIndexes) { this.largestIndexes = largestIndexes; }
    }
}