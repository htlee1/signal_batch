package gc.mda.signal_batch.service;

import gc.mda.signal_batch.common.AreaBoundaryCache;
import gc.mda.signal_batch.dto.websocket.*;
import gc.mda.signal_batch.util.VesselTrackConverter;
import gc.mda.signal_batch.monitoring.TrackStreamingMetrics;
import gc.mda.signal_batch.service.filter.VesselTrackFilter;
import gc.mda.signal_batch.service.simplification.TrackSimplificationStrategy;
import gc.mda.signal_batch.service.simplification.TrackSimplificationStrategy.SimplificationLevel;
import gc.mda.signal_batch.util.ValidationUtil;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StompTrackStreamingService {

    private final DataSource queryDataSource;
    private final JdbcTemplate queryJdbcTemplate;

    private final AreaBoundaryCache areaBoundaryCache;

    private final TrackStreamingMetrics metrics;

    private final VesselTrackFilter vesselTrackFilter;
    
    @Value("${vessel.batch.m-value.read-column:track_geom}")
    private String readColumn;  // MIGRATION_V2: track_geom or track_geom_v2

    // 활성 쿼리 관리
    private final Map<String, QueryContext> activeQueries = new ConcurrentHashMap<>();

    // 병렬 처리를 위한 ExecutorService - 스레드 수 증가 (성능 개선)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    // 궤적 간소화 서비스
    private final TrackSimplificationStrategy simplificationStrategy;

    // 선박 궤적 병합 서비스
    private final VesselTrackMerger vesselTrackMerger;

    // 생성자
    public StompTrackStreamingService(
            @Qualifier("queryDataSource") DataSource queryDataSource,
            @Qualifier("queryJdbcTemplate") JdbcTemplate queryJdbcTemplate,
            AreaBoundaryCache areaBoundaryCache,
            TrackStreamingMetrics metrics,
            VesselTrackFilter vesselTrackFilter,
            TrackSimplificationStrategy simplificationStrategy,
            VesselTrackMerger vesselTrackMerger) {
        this.queryDataSource = queryDataSource;
        this.queryJdbcTemplate = queryJdbcTemplate;
        this.areaBoundaryCache = areaBoundaryCache;
        this.metrics = metrics;
        this.vesselTrackFilter = vesselTrackFilter;
        this.simplificationStrategy = simplificationStrategy;
        this.vesselTrackMerger = vesselTrackMerger;
    }

    @Async
    public void streamTracks(TrackQueryRequest request,
                             String queryId,
                             String sessionId,
                             Consumer<TrackChunkResponse> chunkConsumer,
                             Consumer<QueryStatusUpdate> statusConsumer) {

        QueryContext context = new QueryContext(queryId, sessionId, System.currentTimeMillis());
        activeQueries.put(queryId, context);

        // 메트릭 기록
        Timer.Sample timer = metrics.startTimer();
        context.setTimerSample(timer);
        metrics.recordQueryStarted(queryId, "WEBSOCKET");

        try {
            // 입력 검증
            validateRequest(request);

            // 병합 모드로 처리 (성능 개선) - 항상 병합 모드로 처리
            // if (request.getChunkSize() >= 5000) { // 대용량 청크는 병합 모드
            if (true) { // 항상 병합 모드 사용
                streamMergedTracks(request, queryId, sessionId, chunkConsumer, statusConsumer);
                return;
            }

            // 시간 범위별 테이블 전략 분할
            Map<TableStrategy, List<TimeRange>> strategyMap = splitTimeRangeByStrategy(
                    request.getStartTime(), request.getEndTime()
            );

            log.info("Query {} using strategies: {}", queryId, strategyMap.keySet());

            // 거리/속도 필터링 적용
            Set<String> filteredVessels = null;
            if (hasDistanceOrSpeedFilter(request)) {
                log.info("Applying distance/speed filter for query {}", queryId);
                statusConsumer.accept(new QueryStatusUpdate(
                        queryId, "FILTERING", "Applying distance/speed filters...", 2.0
                ));

                // 전체 테이블에 걸쳐 통합 필터링 수행
                Map<String, List<VesselTrackFilter.TimeRange>> filterStrategyMap = new HashMap<>();
                for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
                    List<VesselTrackFilter.TimeRange> filterTimeRanges = entry.getValue().stream()
                            .map(range -> VesselTrackFilter.TimeRange.builder()
                                    .start(range.getStart())
                                    .end(range.getEnd())
                                    .build())
                            .collect(Collectors.toList());
                    filterStrategyMap.put(entry.getKey().getTableName(), filterTimeRanges);
                }

                filteredVessels = vesselTrackFilter.filterVesselsByDistanceAndSpeedMultipleTables(
                        request, filterStrategyMap
                );

                log.info("Filtered {} vessels for query {}", filteredVessels.size(), queryId);

                // 필터링 후 선박이 없으면 종료
                if (filteredVessels.isEmpty()) {
                    statusConsumer.accept(new QueryStatusUpdate(
                            queryId, "COMPLETED", "No vessels match the distance/speed criteria", 100.0
                    ));
                    return;
                }
            }

            // 전체 트랙 수 계산
            int totalTracks = 0;
            for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
                TableStrategy strategy = entry.getKey();
                List<TimeRange> ranges = entry.getValue();
                int count = countTracksParallel(request, strategy, ranges, filteredVessels);
                totalTracks += count;
                log.info("Strategy {} has {} tracks", strategy, count);
            }

            if (totalTracks == 0) {
                statusConsumer.accept(new QueryStatusUpdate(
                        queryId, "COMPLETED", "No tracks found", 100.0
                ));
                return;
            }

            statusConsumer.accept(new QueryStatusUpdate(
                    queryId, "PROCESSING",
                    String.format("Found %d tracks, starting stream", totalTracks), 5.0
            ));

            // 스트리밍 시작 - 모든 전략 처리
            streamTracksWithMultipleStrategies(request, strategyMap, queryId,
                    totalTracks, chunkConsumer, statusConsumer, context, filteredVessels);

        } catch (Exception e) {
            log.error("Error in track streaming for query: {}", queryId, e);
            metrics.recordQueryError(queryId, e.getClass().getSimpleName());
            statusConsumer.accept(new QueryStatusUpdate(
                    queryId, "ERROR", "Error: " + e.getMessage(), -1.0
            ));
        } finally {
            activeQueries.remove(queryId);
        }
    }

    public void cancelQuery(String queryId) {
        QueryContext context = activeQueries.get(queryId);
        if (context != null) {
            context.cancel();
            log.info("Query {} cancelled", queryId);
        }
    }

    public QueryStatusUpdate getQueryStatus(String queryId) {
        QueryContext context = activeQueries.get(queryId);
        if (context != null) {
            return new QueryStatusUpdate(
                    queryId,
                    context.isActive() ? "PROCESSING" : "CANCELLED",
                    "Query in progress",
                    context.getProgressPercentage()
            );
        }
        return new QueryStatusUpdate(queryId, "NOT_FOUND", "Query not found", -1.0);
    }

    private boolean hasDistanceOrSpeedFilter(TrackQueryRequest request) {
        return request.getMinTotalDistance() != null ||
                request.getMaxTotalDistance() != null ||
                request.getMinAvgSpeed() != null ||
                request.getMaxAvgSpeed() != null;
    }

    private void validateRequest(TrackQueryRequest request) {
        ValidationUtil.validateTrackQueryRequest(request);
    }

    // 개선된 시간 범위별 테이블 선택 로직 (데이터 존재 여부 확인 포함)
    private Map<TableStrategy, List<TimeRange>> splitTimeRangeByStrategy(LocalDateTime start, LocalDateTime end) {
        Map<TableStrategy, List<TimeRange>> strategyMap = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

        // 로그를 위한 시간 측정 시작
        long startTime = System.currentTimeMillis();

        // 5분 데이터: 최근 1시간 이내
        LocalDateTime hourAgo = now.minusHours(1);

        // 1시간 데이터: 1시간 전 ~ 24시간 전  
        LocalDateTime dayAgo = now.minusDays(1);

        // 집계 수준별 시간 범위 분할
        List<TimeRange> fiveMinRanges = new ArrayList<>();
        List<TimeRange> hourlyRanges = new ArrayList<>();
        List<TimeRange> dailyRanges = new ArrayList<>();

        LocalDateTime current = start;

        while (current.isBefore(end)) {
            LocalDateTime rangeEnd;

            // 현재 시간에서 얼마나 오래된 데이터인지에 따라 처리
            if (current.isAfter(hourAgo)) {
                // 최근 1시간 이내 - 5분 단위로 처리
                rangeEnd = current.plusMinutes(5);
                if (rangeEnd.isAfter(end)) rangeEnd = end;
                if (rangeEnd.isAfter(now)) rangeEnd = now;

                fiveMinRanges.add(new TimeRange(current, rangeEnd));
            } else if (current.isAfter(dayAgo)) {
                // 1시간 ~ 24시간 - 시간 단위로 처리
                rangeEnd = current.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                if (rangeEnd.isAfter(end)) rangeEnd = end;

                hourlyRanges.add(new TimeRange(current, rangeEnd));
            } else {
                // 24시간 이전 - 일 단위로 처리 (중요: 일별 테이블은 일 단위로만)
                rangeEnd = current.toLocalDate().plusDays(1).atStartOfDay();
                if (rangeEnd.isAfter(end)) rangeEnd = end;

                // 하루 전체를 하나의 범위로
                LocalDateTime dayStart = current.toLocalDate().atStartOfDay();
                LocalDateTime dayEnd = rangeEnd.toLocalDate().atStartOfDay();
                if (dayEnd.isAfter(end)) dayEnd = end;

                // 같은 날짜가 이미 추가되지 않았는지 확인
                boolean alreadyAdded = dailyRanges.stream()
                        .anyMatch(r -> r.getStart().toLocalDate().equals(dayStart.toLocalDate()));

                if (!alreadyAdded) {
                    dailyRanges.add(new TimeRange(dayStart, dayEnd));
                }
            }

            current = rangeEnd;
        }

        // 결과를 strategyMap에 추가 (데이터 존재 여부 확인 후)
        if (!dailyRanges.isEmpty()) {
            // 일별 범위 병합 (중복 제거)
            List<TimeRange> mergedDailyRanges = mergeDailyRanges(dailyRanges);
            // daily 테이블에 데이터가 있는지 확인
            boolean hasDataInDaily = mergedDailyRanges.stream()
                    .anyMatch(range -> hasDataInTable(TableStrategy.DAILY.getTableName(), range.getStart(), range.getEnd()));

            if (hasDataInDaily) {
                strategyMap.put(TableStrategy.DAILY, mergedDailyRanges);
            } else {
                // daily 테이블에 데이터가 없으면 hourly로 폴백
                log.info("No data found in daily table for ranges {}, falling back to hourly", mergedDailyRanges);
                for (TimeRange dailyRange : mergedDailyRanges) {
                    LocalDateTime hourlyStart = dailyRange.getStart();
                    while (hourlyStart.isBefore(dailyRange.getEnd())) {
                        LocalDateTime rangeEnd = hourlyStart.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                        if (rangeEnd.isAfter(dailyRange.getEnd())) rangeEnd = dailyRange.getEnd();
                        hourlyRanges.add(new TimeRange(hourlyStart, rangeEnd));
                        hourlyStart = rangeEnd;
                    }
                }
            }
        }

        if (!hourlyRanges.isEmpty()) {
            // hourly 테이블에 데이터가 있는지 확인
            List<TimeRange> validHourlyRanges = new ArrayList<>();
            List<TimeRange> fallbackTo5Min = new ArrayList<>();

            for (TimeRange range : hourlyRanges) {
                if (hasDataInTable(TableStrategy.HOURLY.getTableName(), range.getStart(), range.getEnd())) {
                    validHourlyRanges.add(range);
                } else {
                    // hourly 테이블에 데이터가 없으면 5분으로 폴백
                    log.info("No data found in hourly table for range {}, falling back to 5min", range);
                    LocalDateTime fiveMinStart = range.getStart();
                    while (fiveMinStart.isBefore(range.getEnd())) {
                        LocalDateTime rangeEnd = fiveMinStart.plusMinutes(5);
                        if (rangeEnd.isAfter(range.getEnd())) rangeEnd = range.getEnd();
                        fallbackTo5Min.add(new TimeRange(fiveMinStart, rangeEnd));
                        fiveMinStart = rangeEnd;
                    }
                }
            }

            if (!validHourlyRanges.isEmpty()) {
                strategyMap.put(TableStrategy.HOURLY, validHourlyRanges);
            }
            fiveMinRanges.addAll(fallbackTo5Min);
        }

        if (!fiveMinRanges.isEmpty()) {
            strategyMap.put(TableStrategy.FIVE_MINUTE, fiveMinRanges);
        }

        log.info("Time range split - Daily: {} ranges, Hourly: {} ranges, 5min: {} ranges",
                dailyRanges.size(), hourlyRanges.size(), fiveMinRanges.size());

        // 상세 로그
        if (!dailyRanges.isEmpty()) {
            log.info("Daily ranges: {}", dailyRanges);
        }
        if (!hourlyRanges.isEmpty()) {
            log.info("Hourly ranges: {}", hourlyRanges);
        }
        if (!fiveMinRanges.isEmpty()) {
            log.info("5min ranges: {}", fiveMinRanges);
        }

        // 테이블 선택 로직 실행 시간 로깅
        long endTime = System.currentTimeMillis();
        log.info("Table strategy selection took {}ms", endTime - startTime);

        return strategyMap;
    }

    // 테이블에 데이터가 존재하는지 확인
    private boolean hasDataInTable(String tableName, LocalDateTime start, LocalDateTime end) {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + tableName +
                " WHERE time_bucket >= ? AND time_bucket < ? LIMIT 1)";
        try {
            return queryJdbcTemplate.queryForObject(sql, Boolean.class, start, end);
        } catch (Exception e) {
            log.warn("Failed to check data existence in {}: {}", tableName, e.getMessage());
            return false;
        }
    }

    // 일별 범위를 날짜별로 병합
    private List<TimeRange> mergeDailyRanges(List<TimeRange> ranges) {
        Map<LocalDateTime, LocalDateTime> dayMap = new TreeMap<>();

        for (TimeRange range : ranges) {
            LocalDateTime dayStart = range.getStart().toLocalDate().atStartOfDay();
            LocalDateTime currentEnd = dayMap.get(dayStart);

            if (currentEnd == null || range.getEnd().isAfter(currentEnd)) {
                dayMap.put(dayStart, range.getEnd());
            }
        }

        return dayMap.entrySet().stream()
                .map(e -> new TimeRange(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    // 여러 테이블 전략을 처리하는 스트리밍 메서드 (순차적 처리로 개선)
    private void streamTracksWithMultipleStrategies(TrackQueryRequest request,
                                                    Map<TableStrategy, List<TimeRange>> strategyMap,
                                                    String queryId,
                                                    int totalTracks,
                                                    Consumer<TrackChunkResponse> chunkConsumer,
                                                    Consumer<QueryStatusUpdate> statusConsumer,
                                                    QueryContext context,
                                                    Set<String> filteredVessels) throws Exception {

        // 결과를 순서대로 처리하기 위한 큐
        BlockingQueue<TrackChunkResponse> resultQueue = new LinkedBlockingQueue<>(100); // 큐 크기 증가
        AtomicInteger processedTracks = new AtomicInteger(0);
        AtomicInteger chunkIndex = new AtomicInteger(0);

        // 결과 처리 스레드 시작
        CompletableFuture<Void> consumerFuture = startConsumerThread(resultQueue, chunkConsumer,
                statusConsumer, queryId, totalTracks, processedTracks, context);

        try {
            // 5분 데이터 먼저 처리 (FIVE_MINUTE)
            if (strategyMap.containsKey(TableStrategy.FIVE_MINUTE)) {
                log.info("Processing 5-minute data first...");
                processSingleStrategy(request, TableStrategy.FIVE_MINUTE,
                        strategyMap.get(TableStrategy.FIVE_MINUTE), queryId,
                        resultQueue, processedTracks, chunkIndex, totalTracks, filteredVessels);
            }

            // 시간별 데이터 처리 (HOURLY)
            if (strategyMap.containsKey(TableStrategy.HOURLY)) {
                log.info("Processing hourly data...");
                processSingleStrategy(request, TableStrategy.HOURLY,
                        strategyMap.get(TableStrategy.HOURLY), queryId,
                        resultQueue, processedTracks, chunkIndex, totalTracks, filteredVessels);
            }

            // 일별 데이터 처리 (DAILY)
            if (strategyMap.containsKey(TableStrategy.DAILY)) {
                log.info("Processing daily data...");
                processSingleStrategy(request, TableStrategy.DAILY,
                        strategyMap.get(TableStrategy.DAILY), queryId,
                        resultQueue, processedTracks, chunkIndex, totalTracks, filteredVessels);
            }

        } finally {
            // 모든 데이터 처리 완료 신호
            resultQueue.put(new TrackChunkResponse()); // poison pill
        }

        // Consumer 완료 대기
        consumerFuture.join();
    }

    // 단일 전략에 대한 병렬 처리
    private void processSingleStrategy(TrackQueryRequest request,
                                       TableStrategy strategy,
                                       List<TimeRange> ranges,
                                       String queryId,
                                       BlockingQueue<TrackChunkResponse> resultQueue,
                                       AtomicInteger processedTracks,
                                       AtomicInteger chunkIndex,
                                       int totalTracks,
                                       Set<String> filteredVessels) throws Exception {

        // 동일 전략 내에서만 병렬 처리
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (TimeRange range : ranges) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    streamTimeRange(request, strategy, range, queryId,
                            resultQueue, processedTracks, chunkIndex, totalTracks, filteredVessels);
                } catch (Exception e) {
                    log.error("Error processing {} range: {}", strategy, range, e);
                    throw new CompletionException(e);
                }
            }, executorService);

            futures.add(future);
        }

        // 해당 전략의 모든 데이터 처리 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(ex -> {
                    log.error("Error in parallel processing for strategy {}", strategy, ex);
                    return null;
                })
                .join();
    }

    // 결과 처리 스레드
    private CompletableFuture<Void> startConsumerThread(BlockingQueue<TrackChunkResponse> resultQueue,
                                                        Consumer<TrackChunkResponse> chunkConsumer,
                                                        Consumer<QueryStatusUpdate> statusConsumer,
                                                        String queryId,
                                                        int totalTracks,
                                                        AtomicInteger processedTracks,
                                                        QueryContext context) {

        return CompletableFuture.runAsync(() -> {
            try {
                int lastReportedProgress = 0;
                int consecutiveEmptyPolls = 0;

                while ((processedTracks.get() < totalTracks || !resultQueue.isEmpty()) && context.isActive()) {
                    TrackChunkResponse chunk = resultQueue.poll(100, TimeUnit.MILLISECONDS);

                    if (chunk != null) {
                        // poison pill 처리
                        if (chunk.getTracks() == null || chunk.getTracks().isEmpty()) {
                            if (chunk.getQueryId() == null) break; // 종료 신호
                        }

                        consecutiveEmptyPolls = 0;

                        // 마지막 청크 표시
                        if (chunk.getStats() != null &&
                                chunk.getStats().getProcessedTracks() >= totalTracks) {
                            chunk.setIsLastChunk(true);
                        }

                        chunkConsumer.accept(chunk);

                        // 클라이언트 처리 속도를 고려한 지연 - 적당히 조정
                        Thread.sleep(30); // 30ms로 감소

                        // 진행률 업데이트 (5% 단위로)
                        if (chunk.getStats() != null) {
                            int currentProgress = chunk.getStats().getProgressPercentage().intValue();
                            if (currentProgress >= lastReportedProgress + 5) {
                                statusConsumer.accept(new QueryStatusUpdate(
                                        queryId, "PROCESSING",
                                        String.format("Processed %d/%d tracks",
                                                chunk.getStats().getProcessedTracks(), totalTracks),
                                        chunk.getStats().getProgressPercentage()
                                ));
                                lastReportedProgress = currentProgress;
                            }
                        }
                    } else {
                        consecutiveEmptyPolls++;
                        if (consecutiveEmptyPolls > 10) {
                            Thread.sleep(100);
                        }
                    }
                }

                if (context.isActive()) {
                    metrics.recordQueryCompleted(queryId, context.getTimerSample());
                    statusConsumer.accept(new QueryStatusUpdate(
                            queryId, "COMPLETED", "Stream completed successfully", 100.0
                    ));
                } else {
                    metrics.recordQueryCancelled(queryId);
                    statusConsumer.accept(new QueryStatusUpdate(
                            queryId, "CANCELLED", "Query cancelled by user", -1.0
                    ));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Consumer thread interrupted", e);
            }
        });
    }

    private void streamTimeRange(TrackQueryRequest request,
                                 TableStrategy strategy,
                                 TimeRange range,
                                 String queryId,
                                 BlockingQueue<TrackChunkResponse> resultQueue,
                                 AtomicInteger processedTracks,
                                 AtomicInteger chunkIndex,
                                 int totalTracks,
                                 Set<String> filteredVessels) throws Exception {

        // 쿼리 실행 시간 측정 시작
        long queryStartTime = System.currentTimeMillis();

        // 간소화 레벨 결정
        SimplificationLevel simplificationLevel = determineSimplificationLevel(request, List.of(range));

        // Connection을 직접 사용하여 스트리밍
        try (Connection conn = queryDataSource.getConnection()) {
            conn.setAutoCommit(false);

            String sql = buildStreamingQuery(request, strategy, range, filteredVessels, simplificationLevel);

            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {

                // PostgreSQL 스트리밍 설정
                ps.setFetchSize(1000);

                // 파라미터 바인딩
                int paramIndex = 1;
                ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));

                if (request.getViewport() != null) {
                    ViewportFilter vp = request.getViewport();
                    ps.setDouble(paramIndex++, vp.getMinLon());
                    ps.setDouble(paramIndex++, vp.getMinLat());
                    ps.setDouble(paramIndex++, vp.getMaxLon());
                    ps.setDouble(paramIndex++, vp.getMaxLat());
                }

                // 해구/영역 필터를 위한 추가 시간 파라미터
                if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));
                }

                if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    List<VesselTrackData> buffer = new ArrayList<>(request.getChunkSize());

                    while (rs.next() && activeQueries.containsKey(queryId)) {
                        VesselTrackData track = mapResultSetToTrack(rs);
                        // 메모리 기반 추가 간소화
                        track = applyAdditionalSimplification(track, request, simplificationLevel);
                        buffer.add(track);

                        if (buffer.size() >= request.getChunkSize()) {
                            sendChunk(buffer, queryId, chunkIndex, processedTracks,
                                    resultQueue, request.getChunkSize(), totalTracks);
                            buffer.clear();
                        }
                    }

                    // 남은 데이터 전송
                    if (!buffer.isEmpty()) {
                        sendChunk(buffer, queryId, chunkIndex, processedTracks,
                                resultQueue, request.getChunkSize(), totalTracks);
                    }
                }
            }

            // 쿼리 실행 시간 로깅
            long queryEndTime = System.currentTimeMillis();
            log.info("Query execution for {} {} took {}ms - processed {} tracks",
                    strategy, range, queryEndTime - queryStartTime, processedTracks.get());
        }
    }

    private VesselTrackData mapResultSetToTrack(ResultSet rs) throws SQLException {
        VesselTrackData track = new VesselTrackData();
        track.setSigSrcCd(rs.getString("sig_src_cd"));
        track.setTargetId(rs.getString("target_id"));

        // LineStringM을 WKT로 변환
        track.setTrackGeom(rs.getString("track_geom_wkt"));

        track.setDistanceNm(rs.getDouble("distance_nm"));
        track.setAvgSpeed(rs.getDouble("avg_speed"));
        track.setMaxSpeed(rs.getDouble("max_speed"));
        track.setPointCount(rs.getInt("point_count"));

        // JSON에서 시간 정보 추출
        String startTimeStr = rs.getString("start_time");
        String endTimeStr = rs.getString("end_time");

        if (startTimeStr != null) {
            track.setStartTime(LocalDateTime.parse(startTimeStr.replace(" ", "T")));
        }
        if (endTimeStr != null) {
            track.setEndTime(LocalDateTime.parse(endTimeStr.replace(" ", "T")));
        }

        return track;
    }

    private void sendChunk(List<VesselTrackData> tracks,
                           String queryId,
                           AtomicInteger chunkIndex,
                           AtomicInteger processedTracks,
                           BlockingQueue<TrackChunkResponse> resultQueue,
                           int totalChunkSize,
                           int totalTracks) throws InterruptedException {

        int currentChunkIndex = chunkIndex.getAndIncrement();
        int currentProcessed = processedTracks.addAndGet(tracks.size());

        TrackChunkResponse chunk = new TrackChunkResponse();
        chunk.setQueryId(queryId);
        chunk.setChunkIndex(currentChunkIndex);
        chunk.setTracks(new ArrayList<>(tracks)); // 복사본 생성
        chunk.setIsLastChunk(false);

        ChunkStats stats = new ChunkStats();
        stats.setProcessedTracks(currentProcessed);
        stats.setTotalTracks(totalTracks);
        stats.setProgressPercentage((double) currentProcessed / totalTracks * 100);
        stats.setElapsedMillis(System.currentTimeMillis() -
                activeQueries.get(queryId).getStartTime());
        chunk.setStats(stats);

        // 메트릭 기록
        long chunkStartTime = System.currentTimeMillis();
        resultQueue.put(chunk);
        metrics.recordChunkProcessed(queryId, tracks.size(),
                System.currentTimeMillis() - chunkStartTime);

        // 전송 속도 제어 - 버퍼 누적 방지
        // 청크 크기에 따라 동적으로 지연 시간 조정
        int delayMs = calculateChunkDelay(tracks.size(), totalTracks);
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }

    // 청크 전송 지연 시간 계산
    private int calculateChunkDelay(int chunkSize, int totalTracks) {
        // 대량의 데이터일수록 지연 시간 증가
        if (totalTracks > 1000000) {
            return 200; // 200ms 지연
        } else if (totalTracks > 500000) {
            return 150; // 150ms 지연  
        } else if (totalTracks > 100000) {
            return 100;  // 100ms 지연
        } else if (totalTracks > 50000) {
            return 50;  // 50ms 지연
        } else if (totalTracks > 10000) {
            return 30;  // 30ms 지연
        }
        return 10; // 기본 10ms
    }

    private String buildStreamingQuery(TrackQueryRequest request,
                                       TableStrategy strategy,
                                       TimeRange range,
                                       Set<String> filteredVessels,
                                       SimplificationLevel simplificationLevel) {
        StringBuilder sql = new StringBuilder();

        // 단순화 옵션 적용
        String geomColumn = readColumn; // MIGRATION_V2: 동적 컴럼 선택
        if (simplificationLevel != SimplificationLevel.NONE && simplificationLevel.getTolerance() > 0) {
            sql.append("SELECT sig_src_cd, target_id, ");
            sql.append("ST_AsText(ST_Simplify(").append(geomColumn).append(", ").append(simplificationLevel.getTolerance())
                    .append(")) as track_geom_wkt, ");
        } else {
            sql.append("SELECT sig_src_cd, target_id, ");
            sql.append("ST_AsText(").append(geomColumn).append(") as track_geom_wkt, ");
        }

        sql.append("distance_nm, avg_speed, max_speed, point_count, ");
        sql.append("start_position->>'time' as start_time, ");
        sql.append("end_position->>'time' as end_time ");
        sql.append("FROM ").append(strategy.getTableName()).append(" ");
        sql.append("WHERE time_bucket >= ? AND time_bucket < ? ");

        // Viewport 필터
        if (request.getViewport() != null) {
            sql.append("AND ST_Intersects(").append(geomColumn).append(", ST_MakeEnvelope(?, ?, ?, ?, 4326)) ");
        }

        // 해구 필터 (JOIN 대신 IN 사용으로 성능 개선)
        if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
            sql.append("AND (sig_src_cd, target_id, time_bucket) IN (");
            sql.append("SELECT sig_src_cd, target_id, time_bucket ");
            sql.append("FROM t_grid_vessel_tracks ");
            sql.append("WHERE haegu_no = ANY(ARRAY[").append(
                    request.getHaeguNumbers().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))).append("]) ");
            sql.append("AND time_bucket >= ? AND time_bucket < ?) ");
        }

        // 영역 필터
        if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
            sql.append("AND (sig_src_cd, target_id, time_bucket) IN (");
            sql.append("SELECT sig_src_cd, target_id, time_bucket ");
            sql.append("FROM t_area_vessel_tracks ");
            sql.append("WHERE area_id = ANY(ARRAY['").append(
                    String.join("','", request.getAreaIds())).append("']) ");
            sql.append("AND time_bucket >= ? AND time_bucket < ?) ");
        }

        // 선박 ID 필터
        if (request.getVesselIds() != null && !request.getVesselIds().isEmpty()) {
            sql.append("AND target_id = ANY(ARRAY['").append(
                    String.join("','", request.getVesselIds())).append("']) ");
        }

        // 거리/속도 필터링된 선박 목록
        if (filteredVessels != null && !filteredVessels.isEmpty()) {
            sql.append("AND (sig_src_cd || '_' || target_id) = ANY(ARRAY['").append(
                    String.join("','", filteredVessels)).append("']) ");
        }

        // 인덱스 활용을 위한 정렬
        sql.append("ORDER BY time_bucket, target_id");

        return sql.toString();
    }

    private String buildCountQuery(TrackQueryRequest request, TableStrategy strategy, Set<String> filteredVessels) {
        StringBuilder sql = new StringBuilder();
        String geomColumn = readColumn; // MIGRATION_V2
        sql.append("SELECT COUNT(*) ");
        sql.append("FROM ").append(strategy.getTableName()).append(" ");
        sql.append("WHERE time_bucket >= ? AND time_bucket < ? ");

        // 필터 조건 추가 (buildStreamingQuery와 동일)
        if (request.getViewport() != null) {
            sql.append("AND ST_Intersects(").append(geomColumn).append(", ST_MakeEnvelope(?, ?, ?, ?, 4326)) ");
        }

        if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
            sql.append("AND (sig_src_cd, target_id, time_bucket) IN (");
            sql.append("SELECT sig_src_cd, target_id, time_bucket ");
            sql.append("FROM t_grid_vessel_tracks ");
            sql.append("WHERE haegu_no = ANY(ARRAY[").append(
                    request.getHaeguNumbers().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))).append("]) ");
            sql.append("AND time_bucket >= ? AND time_bucket < ?) ");
        }

        if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
            sql.append("AND (sig_src_cd, target_id, time_bucket) IN (");
            sql.append("SELECT sig_src_cd, target_id, time_bucket ");
            sql.append("FROM t_area_vessel_tracks ");
            sql.append("WHERE area_id = ANY(ARRAY['").append(
                    String.join("','", request.getAreaIds())).append("']) ");
            sql.append("AND time_bucket >= ? AND time_bucket < ?) ");
        }

        if (request.getVesselIds() != null && !request.getVesselIds().isEmpty()) {
            sql.append("AND target_id = ANY(ARRAY['").append(
                    String.join("','", request.getVesselIds())).append("']) ");
        }

        // 거리/속도 필터링된 선박 목록
        if (filteredVessels != null && !filteredVessels.isEmpty()) {
            sql.append("AND (sig_src_cd || '_' || target_id) = ANY(ARRAY['").append(
                    String.join("','", filteredVessels)).append("']) ");
        }

        return sql.toString();
    }

    // 시간 범위 분할 (병렬 처리를 위해)
    private List<TimeRange> splitTimeRange(LocalDateTime start,
                                           LocalDateTime end,
                                           TableStrategy strategy) {
        List<TimeRange> ranges = new ArrayList<>();
        Duration chunkDuration = strategy.getChunkDuration();

        LocalDateTime current = start;
        while (current.isBefore(end)) {
            LocalDateTime chunkEnd = current.plus(chunkDuration);
            if (chunkEnd.isAfter(end)) {
                chunkEnd = end;
            }
            ranges.add(new TimeRange(current, chunkEnd));
            current = chunkEnd;
        }

        return ranges;
    }

    // 병렬 카운트 쿼리
    private int countTracksParallel(TrackQueryRequest request,
                                    TableStrategy strategy,
                                    List<TimeRange> timeRanges,
                                    Set<String> filteredVessels) throws Exception {
        List<CompletableFuture<Integer>> futures = timeRanges.stream()
                .map(range -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return countTracksInRange(request, strategy, range, filteredVessels);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, executorService))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private int countTracksInRange(TrackQueryRequest request,
                                   TableStrategy strategy,
                                   TimeRange range,
                                   Set<String> filteredVessels) {
        String sql = buildCountQuery(request, strategy, filteredVessels);

        List<Object> params = new ArrayList<>();
        params.add(range.getStart());
        params.add(range.getEnd());

        if (request.getViewport() != null) {
            ViewportFilter vp = request.getViewport();
            params.add(vp.getMinLon());
            params.add(vp.getMinLat());
            params.add(vp.getMaxLon());
            params.add(vp.getMaxLat());
        }

        if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
            params.add(range.getStart());
            params.add(range.getEnd());
        }

        if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
            params.add(range.getStart());
            params.add(range.getEnd());
        }

        return queryJdbcTemplate.queryForObject(sql, Integer.class, params.toArray());
    }

    // 내부 클래스들
    @Getter
    private static class QueryContext {
        private final String queryId;
        private final String sessionId;
        private final long startTime;
        private volatile boolean active = true;
        private volatile double progressPercentage = 0.0;
        private Timer.Sample timerSample;

        public QueryContext(String queryId, String sessionId, long startTime) {
            this.queryId = queryId;
            this.sessionId = sessionId;
            this.startTime = startTime;
        }

        public boolean isCancelled() {
            return !active;
        }

        public void cancel() {
            this.active = false;
        }

        public void updateProgress(double progress) {
            this.progressPercentage = progress;
        }

        public void setTimerSample(Timer.Sample timerSample) {
            this.timerSample = timerSample;
        }
    }

    @Getter
    private static class TimeRange {
        private final LocalDateTime start;
        private final LocalDateTime end;

        public TimeRange(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return String.format("[%s to %s]", start, end);
        }
    }

    private enum TableStrategy {
        FIVE_MINUTE("t_vessel_tracks_5min", "5 minutes", Duration.ofHours(1)),
        HOURLY("t_vessel_tracks_hourly", "1 hour", Duration.ofDays(1)),
        DAILY("t_vessel_tracks_daily", "1 day", Duration.ofDays(7));

        @Getter
        private final String tableName;
        @Getter
        private final String interval;
        @Getter
        private final Duration chunkDuration;

        TableStrategy(String tableName, String interval, Duration chunkDuration) {
            this.tableName = tableName;
            this.interval = interval;
            this.chunkDuration = chunkDuration;
        }
    }

    /**
     * 병합 모드로 궤적 스트리밍 처리
     * 선박별로 데이터를 병합하여 전송하여 프론트엔드의 부하를 줄임
     */
    private void streamMergedTracks(TrackQueryRequest request,
                                    String queryId,
                                    String sessionId,
                                    Consumer<TrackChunkResponse> chunkConsumer,
                                    Consumer<QueryStatusUpdate> statusConsumer) throws Exception {

        QueryContext context = activeQueries.get(queryId);

        // 시간 범위별 테이블 전략 분할
        Map<TableStrategy, List<TimeRange>> strategyMap = splitTimeRangeByStrategy(
                request.getStartTime(), request.getEndTime()
        );

        log.info("Merged mode - Query {} using strategies: {}", queryId, strategyMap.keySet());

        // 거리/속도 필터링 적용
        Set<String> filteredVessels = null;
        if (hasDistanceOrSpeedFilter(request)) {
            log.info("Applying distance/speed filter for query {}", queryId);
            statusConsumer.accept(new QueryStatusUpdate(
                    queryId, "FILTERING", "Applying distance/speed filters...", 2.0
            ));

            Map<String, List<VesselTrackFilter.TimeRange>> filterStrategyMap = new HashMap<>();
            for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
                List<VesselTrackFilter.TimeRange> filterTimeRanges = entry.getValue().stream()
                        .map(range -> VesselTrackFilter.TimeRange.builder()
                                .start(range.getStart())
                                .end(range.getEnd())
                                .build())
                        .collect(Collectors.toList());
                filterStrategyMap.put(entry.getKey().getTableName(), filterTimeRanges);
            }

            filteredVessels = vesselTrackFilter.filterVesselsByDistanceAndSpeedMultipleTables(
                    request, filterStrategyMap
            );

            log.info("Filtered {} vessels for query {}", filteredVessels.size(), queryId);

            if (filteredVessels.isEmpty()) {
                statusConsumer.accept(new QueryStatusUpdate(
                        queryId, "COMPLETED", "No vessels match the distance/speed criteria", 100.0
                ));
                return;
            }
        }

        // 데이터 수집 및 병합
        List<VesselTrackData> allTracks = new ArrayList<>();

        // 전체 트랙 수 계산
        int totalTracks = 0;
        for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
            TableStrategy strategy = entry.getKey();
            List<TimeRange> ranges = entry.getValue();
            int count = countTracksParallel(request, strategy, ranges, filteredVessels);
            totalTracks += count;
            log.info("Strategy {} has {} tracks", strategy, count);
        }

        if (totalTracks == 0) {
            statusConsumer.accept(new QueryStatusUpdate(
                    queryId, "COMPLETED", "No tracks found", 100.0
            ));
            return;
        }

        statusConsumer.accept(new QueryStatusUpdate(
                queryId, "PROCESSING",
                String.format("Found %d tracks, collecting for merge", totalTracks), 5.0
        ));

        // 모든 데이터 수집
        for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
            TableStrategy strategy = entry.getKey();
            List<TimeRange> ranges = entry.getValue();

            for (TimeRange range : ranges) {
                List<VesselTrackData> rangeTracks = collectTracksInRange(
                        request, strategy, range, filteredVessels
                );
                allTracks.addAll(rangeTracks);
            }
        }

        log.info("Collected {} tracks for merging", allTracks.size());

        // 선박별 병합
        List<MergedVesselTrack> mergedTracks = vesselTrackMerger.mergeTracksByVessel(allTracks);
        log.info("Merged into {} vessel tracks", mergedTracks.size());

        // 병합된 데이터를 청크로 전송
        int chunkIndex = 0;
        int processedVessels = 0;
        List<MergedVesselTrack> buffer = new ArrayList<>();

        for (MergedVesselTrack mergedTrack : mergedTracks) {
            buffer.add(mergedTrack);

            // 버퍼가 가득 차면 전송 (선박 단위로 청크 크기 조정)
            if (buffer.size() >= 100) { // 100척씩 전송
                sendMergedChunk(buffer, queryId, chunkIndex++, processedVessels,
                        mergedTracks.size(), chunkConsumer);
                processedVessels += buffer.size();
                buffer.clear();

                // 진행률 업데이트
                double progress = (double) processedVessels / mergedTracks.size() * 100;
                statusConsumer.accept(new QueryStatusUpdate(
                        queryId, "PROCESSING",
                        String.format("Processed %d/%d vessels", processedVessels, mergedTracks.size()),
                        progress
                ));
            }
        }

        // 남은 데이터 전송
        if (!buffer.isEmpty()) {
            sendMergedChunk(buffer, queryId, chunkIndex++, processedVessels,
                    mergedTracks.size(), chunkConsumer);
        }

        // 완료 처리
        metrics.recordQueryCompleted(queryId, context.getTimerSample());
        statusConsumer.accept(new QueryStatusUpdate(
                queryId, "COMPLETED", "Stream completed successfully", 100.0
        ));
    }

    /**
     * 시간 범위 내 모든 트랙 수집
     */
    private List<VesselTrackData> collectTracksInRange(TrackQueryRequest request,
                                                       TableStrategy strategy,
                                                       TimeRange range,
                                                       Set<String> filteredVessels) {
        List<VesselTrackData> tracks = new ArrayList<>();

        try (Connection conn = queryDataSource.getConnection()) {
            SimplificationLevel level = determineSimplificationLevel(request, List.of(range));
            String sql = buildStreamingQuery(request, strategy, range, filteredVessels, level);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // 파라미터 바인딩
                int paramIndex = 1;
                ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));

                if (request.getViewport() != null) {
                    ViewportFilter vp = request.getViewport();
                    ps.setDouble(paramIndex++, vp.getMinLon());
                    ps.setDouble(paramIndex++, vp.getMinLat());
                    ps.setDouble(paramIndex++, vp.getMaxLon());
                    ps.setDouble(paramIndex++, vp.getMaxLat());
                }

                if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));
                }

                if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getStart()));
                    ps.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf(range.getEnd()));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        VesselTrackData track = mapResultSetToTrack(rs);
                        track = applyAdditionalSimplification(track, request, level);
                        tracks.add(track);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error collecting tracks for range {}: {}", range, e.getMessage());
        }

        return tracks;
    }

    /**
     * 병합된 청크 전송
     */
    private void sendMergedChunk(List<MergedVesselTrack> mergedTracks,
                                 String queryId,
                                 int chunkIndex,
                                 int processedVessels,
                                 int totalVessels,
                                 Consumer<TrackChunkResponse> chunkConsumer) {

        // MergedVesselTrack을 VesselTrackData로 변환
        List<VesselTrackData> vesselTracks = mergedTracks.stream()
                .map(merged -> {
                    VesselTrackData track = new VesselTrackData();
                    track.setSigSrcCd(merged.getSigSrcCd());
                    track.setTargetId(merged.getTargetId());
                    track.setTrackGeom(merged.getMergedTrackGeom());
                    track.setDistanceNm(merged.getTotalDistanceNm());
                    track.setAvgSpeed(merged.getAvgSpeed());
                    track.setStartTime(merged.getStartTime());
                    track.setEndTime(merged.getEndTime());
                    track.setPointCount(merged.getTotalPoints());
                    return track;
                })
                .collect(Collectors.toList());

        TrackChunkResponse chunk = new TrackChunkResponse();
        chunk.setQueryId(queryId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setTracks(vesselTracks);
        chunk.setIsLastChunk(processedVessels + mergedTracks.size() >= totalVessels);

        ChunkStats stats = new ChunkStats();
        stats.setProcessedTracks(processedVessels + mergedTracks.size());
        stats.setTotalTracks(totalVessels);
        stats.setProgressPercentage((double) (processedVessels + mergedTracks.size()) / totalVessels * 100);
        chunk.setStats(stats);

        chunkConsumer.accept(chunk);
    }

    /**
     * 간소화 레벨 결정
     */
    private SimplificationLevel determineSimplificationLevel(TrackQueryRequest request,
                                                             List<TimeRange> ranges) {
        if (ranges.isEmpty()) {
            return SimplificationLevel.NONE;
        }

        // 전체 시간 범위 계산
        LocalDateTime minTime = ranges.stream()
                .map(TimeRange::getStart)
                .min(LocalDateTime::compareTo)
                .orElse(request.getStartTime());

        LocalDateTime maxTime = ranges.stream()
                .map(TimeRange::getEnd)
                .max(LocalDateTime::compareTo)
                .orElse(request.getEndTime());

        // 뷰포트 정보
        double minLon = -180, maxLon = 180, minLat = -90, maxLat = 90;
        if (request.getViewport() != null) {
            ViewportFilter vp = request.getViewport();
            minLon = vp.getMinLon();
            maxLon = vp.getMaxLon();
            minLat = vp.getMinLat();
            maxLat = vp.getMaxLat();
        }

        // 간소화 모드 확인
        if (request.getSimplificationMode() != null) {
            switch (request.getSimplificationMode().toString()) {
                case "NONE":
                    return SimplificationLevel.NONE;
                case "ADAPTIVE":
                    // adaptiveSimplify 사용
                    break;
                case "AGGRESSIVE":
                    return SimplificationLevel.EXTREME;
            }
        }

        // TrackSimplificationStrategy를 사용하여 레벨 결정
        return simplificationStrategy.determineLevel(minTime, maxTime, minLon, maxLon, minLat, maxLat);
    }

    /**
     * 메모리 기반 추가 간소화 적용
     */
    private VesselTrackData applyAdditionalSimplification(VesselTrackData track,
                                                          TrackQueryRequest request,
                                                          SimplificationLevel level) {
        // 이미 충분히 간소화된 경우 추가 처리 없음
        if (level == SimplificationLevel.EXTREME ||
                track.getPointCount() <= 100) {
            return track;
        }

        // maxPointsPerTrack 설정이 있는 경우
        if (request.getMaxPointsPerTrack() != null &&
                track.getPointCount() > request.getMaxPointsPerTrack()) {

            String simplifiedGeom = simplificationStrategy.simplifyByPointCount(
                    track.getTrackGeom(), request.getMaxPointsPerTrack()
            );
            track.setTrackGeom(simplifiedGeom);
        }

        // maxResponseSizeKB 설정이 있는 경우
        if (request.getMaxResponseSizeKB() != null) {
            double trackSizeKB = (track.getPointCount() * 24.0) / 1024.0;
            if (trackSizeKB > request.getMaxResponseSizeKB() / 100) { // 트랙당 할당량
                String adaptiveGeom = simplificationStrategy.adaptiveSimplify(
                        track.getTrackGeom(), request.getMaxResponseSizeKB() / 100
                );
                track.setTrackGeom(adaptiveGeom);
            }
        }

        return track;
    }
}
