package gc.mda.signal_batch.service;

import gc.mda.signal_batch.util.ShipKindCodeConverter;
import gc.mda.signal_batch.dto.websocket.TrackChunkResponse;
import gc.mda.signal_batch.dto.CompactVesselTrack;
import gc.mda.signal_batch.dto.websocket.TrackQueryRequest;
import gc.mda.signal_batch.dto.websocket.ChunkStats;
import gc.mda.signal_batch.service.simplification.TrackSimplificationStrategy;
import gc.mda.signal_batch.service.simplification.TrackSimplificationStrategy.SimplificationLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import gc.mda.signal_batch.dto.websocket.QueryStatusUpdate;
import gc.mda.signal_batch.dto.websocket.ViewportFilter;
import org.springframework.scheduling.annotation.Async;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 청크 기반 궤적 스트리밍 서비스
 * LineStringM을 압축된 배열 형태로 변환하여 전송
 */
@Slf4j
@Service
public class ChunkedTrackStreamingService {

    private final JdbcTemplate queryJdbcTemplate;
    private final DataSource queryDataSource;
    private final TrackSimplificationStrategy simplificationStrategy;
    private final WKTReader wktReader = new WKTReader();
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TRACKS_PER_CHUNK = 20000; // 청크당 최대 트랙 수 (버퍼 오버플로우 방지)
    private static final int MAX_MESSAGE_SIZE_KB = 1024; // 메시지당 최대 크기 1MB로 축소
    private static final int MIN_MESSAGE_SIZE_KB = 256;  // 최소 메시지 크기 256KB로 축소

    // 선박 정보 캐시 (TTL: 1시간)
    private final ConcurrentHashMap<String, VesselInfo> vesselInfoCache = new ConcurrentHashMap<>();
    private static final long VESSEL_CACHE_TTL = 3600_000; // 1시간
    private volatile long lastCacheCleanup = System.currentTimeMillis();

    // 진행률 추적용 변수
    private int estimatedTotalMinutes = 0;
    private long queryStartTime = 0;
    private final Map<String, Integer> processedTimeRanges = new HashMap<>();
    private final AtomicLong pendingBufferSize = new AtomicLong(0);
    private static final long MAX_PENDING_BUFFER = 50 * 1024 * 1024; // 50MB
    private static final long WARNING_BUFFER_THRESHOLD = 40 * 1024 * 1024; // 40MB (80%)

    // 백프레셔 관련 변수
    private final Map<String, BackpressureMetrics> queryMetrics = new ConcurrentHashMap<>();
    private volatile int currentChunkSizeKB = MAX_MESSAGE_SIZE_KB;
    
    // track_geom 고정 사용

    public ChunkedTrackStreamingService(
            @Qualifier("queryJdbcTemplate") JdbcTemplate queryJdbcTemplate,
            @Qualifier("queryDataSource") DataSource queryDataSource,
            TrackSimplificationStrategy simplificationStrategy) {
        this.queryJdbcTemplate = queryJdbcTemplate;
        this.queryDataSource = queryDataSource;
        this.simplificationStrategy = simplificationStrategy;
    }

    /**
     * 선박 정보 캐시용 내부 클래스
     */
    private static class VesselInfo {
        String shipName;
        String shipType;
        long cacheTime;

        VesselInfo(String shipName, String shipType) {
            this.shipName = shipName != null ? shipName : "-";
            this.shipType = shipType != null ? shipType : "-";
            this.cacheTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cacheTime > VESSEL_CACHE_TTL;
        }
    }

    /**
     * 백프레셔 메트릭스 추적용 내부 클래스
     */
    private static class BackpressureMetrics {
        private final AtomicLong totalBytes = new AtomicLong(0);
        private final AtomicInteger chunkCount = new AtomicInteger(0);
        private final AtomicInteger bufferWarnings = new AtomicInteger(0);
        private final AtomicInteger backpressureEvents = new AtomicInteger(0);
        private volatile long lastWarningTime = 0;
        private volatile int dynamicChunkSizeKB = MAX_MESSAGE_SIZE_KB;
    }

    /**
     * 테이블 범위 처리 - LineStringM을 압축된 배열로 변환
     */
    // 선박 데이터 누적용 내부 클래스
    private static class VesselAccumulator {
        String sigSrcCd;
        String targetId;
        String shipName;  // 선명 추가
        String shipType;  // 선종 추가
        String shipKindCode;  // 선박 종류 코드 추가
        List<double[]> geometry = new ArrayList<>(500);
        List<String> timestamps = new ArrayList<>(500);
        List<Double> speeds = new ArrayList<>(500);
        double totalDistance = 0;
        double maxSpeed = 0;
        int pointCount = 0;
    }

    /**
     * 선박 정보 조회 (캐시 우선)
     */
    private VesselInfo getVesselInfo(String sigSrcCd, String targetId) {
        String vesselKey = sigSrcCd + "_" + targetId;

        // 캐시 청소 (10분마다)
        if (System.currentTimeMillis() - lastCacheCleanup > 600_000) {
            cleanupVesselCache();
        }

        // 캐시에서 조회
        VesselInfo cached = vesselInfoCache.get(vesselKey);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        // DB에서 조회
        try {
            String sql = "SELECT ship_nm, ship_ty FROM signal.t_vessel_latest_position " +
                    "WHERE sig_src_cd = ? AND target_id = ?";

            VesselInfo info = queryJdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> new VesselInfo(
                            rs.getString("ship_nm"),
                            rs.getString("ship_ty")
                    ),
                    sigSrcCd, targetId
            );

            // 캐시에 저장
            vesselInfoCache.put(vesselKey, info);
            log.debug("Vessel info loaded from DB and cached: {} - {} ({})",
                    vesselKey, info.shipName, info.shipType);
            return info;

        } catch (Exception e) {
            log.debug("No vessel info found for {}, using defaults", vesselKey);
            VesselInfo defaultInfo = new VesselInfo(null, null);
            // 기본값도 캐시에 저장 (DB 부하 감소)
            vesselInfoCache.put(vesselKey, defaultInfo);
            return defaultInfo;
        }
    }

    /**
     * 선박 캐시 정리
     */
    private void cleanupVesselCache() {
        int before = vesselInfoCache.size();
        vesselInfoCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int after = vesselInfoCache.size();
        lastCacheCleanup = System.currentTimeMillis();
        log.info("Vessel cache cleanup: {} -> {} entries", before, after);
    }

    /**
     * 선박 정보 배치 조회
     */
    private Map<String, VesselInfo> batchGetVesselInfo(Set<String> vesselIds) {
        Map<String, VesselInfo> result = new HashMap<>();
        List<String> uncachedIds = new ArrayList<>();

        // 캐시 확인
        for (String vesselId : vesselIds) {
            VesselInfo cached = vesselInfoCache.get(vesselId);
            if (cached != null && !cached.isExpired()) {
                result.put(vesselId, cached);
            } else {
                uncachedIds.add(vesselId);
            }
        }

        // 캐시에 없는 것들은 DB에서 배치 조회
        if (!uncachedIds.isEmpty()) {
            try {
                String sql = "SELECT sig_src_cd, target_id, ship_nm, ship_ty " +
                        "FROM signal.t_vessel_latest_position " +
                        "WHERE sig_src_cd || '_' || target_id IN (" +
                        String.join(",", Collections.nCopies(uncachedIds.size(), "?")) + ")";

                queryJdbcTemplate.query(sql, rs -> {
                    String vesselId = rs.getString("sig_src_cd") + "_" + rs.getString("target_id");
                    VesselInfo info = new VesselInfo(
                            rs.getString("ship_nm"),
                            rs.getString("ship_ty")
                    );
                    result.put(vesselId, info);
                    vesselInfoCache.put(vesselId, info);
                }, uncachedIds.toArray());

                log.info("Batch loaded {} vessel infos from DB", result.size() - (vesselIds.size() - uncachedIds.size()));
            } catch (Exception e) {
                log.warn("Failed to batch load vessel info: {}", e.getMessage());
                // 기본값 설정
                for (String vesselId : uncachedIds) {
                    if (!result.containsKey(vesselId)) {
                        VesselInfo defaultInfo = new VesselInfo(null, null);
                        result.put(vesselId, defaultInfo);
                        vesselInfoCache.put(vesselId, defaultInfo);
                    }
                }
            }
        }

        return result;
    }

    private List<CompactVesselTrack> processTableRange(TrackQueryRequest request, TableStrategy strategy, TimeRange range) {
        Map<String, VesselAccumulator> vesselMap = new HashMap<>(20000); // 예상 선박 수

        String tableName = strategy.getTableName();

        // 간소화 레벨 결정
        SimplificationLevel simplificationLevel = determineSimplificationLevel(request,
                new TimeChunk(range.getStart(), range.getEnd()));
        log.info("Using {} table with simplification level {} for range [{} - {}]",
                strategy, simplificationLevel, range.getStart(), range.getEnd());

        String sql = buildRangeQuery(tableName, request, range, simplificationLevel);

        // Connection을 직접 사용하여 스트리밍 처리
        try (Connection conn = queryDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // 파라미터 바인딩
            int paramIndex = 1;
            ps.setTimestamp(paramIndex++, Timestamp.valueOf(range.getStart()));
            ps.setTimestamp(paramIndex++, Timestamp.valueOf(range.getEnd()));

            if (request.getViewport() != null) {
                ViewportFilter vp = request.getViewport();
                ps.setDouble(paramIndex++, vp.getMinLon());
                ps.setDouble(paramIndex++, vp.getMinLat());
                ps.setDouble(paramIndex++, vp.getMaxLon());
                ps.setDouble(paramIndex++, vp.getMaxLat());
            }

            // PostgreSQL 스트리밍 설정
            // FetchSize 최적화 - 메모리가 충분하므로 기존 유지
            ps.setFetchSize(5000);

            try (ResultSet rs = ps.executeQuery()) {
                int trackCount = 0;
                int vesselCount = 0;

                while (rs.next() && trackCount < MAX_TRACKS_PER_CHUNK) {
                    String sigSrcCd = rs.getString("sig_src_cd");
                    String targetId = rs.getString("target_id");
                    String vesselId = sigSrcCd + "_" + targetId;

                    // LineStringM 파싱
                    String trackGeomWkt = rs.getString("track_geom");
                    Timestamp timeBucket = rs.getTimestamp("time_bucket");

                    // start_position, end_position에서 시간 정보 추출
                    String startTimeStr = null;
                    String endTimeStr = null;
                    try {
                        startTimeStr = rs.getString("start_time");
                        endTimeStr = rs.getString("end_time");
                    } catch (SQLException ignored) {
                        // 5min 테이블은 이 필드가 없을 수 있음
                    }

                    if (trackGeomWkt != null && !trackGeomWkt.isEmpty() && !"LINESTRING EMPTY".equals(trackGeomWkt)) {
                        try {
                            LineString lineString = (LineString) wktReader.read(trackGeomWkt);

                            // 빈 LineString 처리
                            if (lineString.getNumPoints() == 0) {
                                continue;
                            }

                            // 좌표 배열로 변환
                            List<double[]> geometry = new ArrayList<>();
                            List<String> timestamps = new ArrayList<>();
                            List<Double> speeds = new ArrayList<>();

                            // 기준 시간 결정 (start_time이 있으면 사용, 없으면 timeBucket 사용)
                            LocalDateTime baseTime = timeBucket.toLocalDateTime();
                            if (startTimeStr != null && !startTimeStr.isEmpty()) {
                                try {
                                    baseTime = LocalDateTime.parse(startTimeStr, TIMESTAMP_FORMATTER);
                                } catch (Exception e) {
                                    log.debug("Failed to parse start_time: {}", startTimeStr);
                                }
                            }

                            Coordinate prevCoord = null;
                            long prevTimeMillis = 0;

                            for (int i = 0; i < lineString.getNumPoints(); i++) {
                                Coordinate coord = lineString.getCoordinateN(i);
                                geometry.add(new double[]{coord.x, coord.y});

                                // Unix timestamp (초 단위) - String으로 저장
                                long unixTimestamp = (long)coord.getM();
                                timestamps.add(String.valueOf(unixTimestamp));

                                // 속도 계산 (두 점 사이의 거리와 시간차를 이용)
                                double speed = 0.0;
                                long currentTimeMillis;
                                
                                // Unix timestamp (초 단위를 밀리초로 변환)
                                currentTimeMillis = (long)coord.getM() * 1000;
                                
                                if (prevCoord != null && i > 0) {
                                    double distance = calculateDistance(prevCoord, coord); // 해리(nm)
                                    double timeDiff = (currentTimeMillis - prevTimeMillis) / 3600000.0; // 시간(hour)
                                    if (timeDiff > 0) {
                                        speed = distance / timeDiff; // knots
                                    }
                                }
                                speeds.add(speed);

                                prevCoord = coord;
                                prevTimeMillis = currentTimeMillis;
                            }

                            // 선박별 데이터 병합
                            VesselAccumulator accumulator = vesselMap.get(vesselId);
                            if (accumulator == null) {
                                vesselCount++; // 새 선박 추가 시 카운트
                                accumulator = new VesselAccumulator();
                                accumulator.sigSrcCd = sigSrcCd;
                                accumulator.targetId = targetId;

                                // 선박 정보 조회 (캐시 우선)
                                VesselInfo vesselInfo = getVesselInfo(sigSrcCd, targetId);
                                accumulator.shipName = vesselInfo.shipName;
                                accumulator.shipType = vesselInfo.shipType;

                                // shipKindCode 계산
                                accumulator.shipKindCode = ShipKindCodeConverter.getShipKindCode(sigSrcCd, vesselInfo.shipType);

                                // 테스트용 로그 - 처음 10개 선박만
//                                if (vesselCount <= 10) {
//                                    log.info("[VESSEL_INFO] {} - Name: {}, Type: {}",
//                                            vesselId, vesselInfo.shipName, vesselInfo.shipType);
//                                }

                                vesselMap.put(vesselId, accumulator);
                            }

                            // 데이터 직접 추가 (성능 개선)
                            accumulator.geometry.addAll(geometry);
                            accumulator.timestamps.addAll(timestamps);
                            accumulator.speeds.addAll(speeds);
                            accumulator.totalDistance += rs.getDouble("distance_nm");
                            accumulator.maxSpeed = Math.max(accumulator.maxSpeed, rs.getDouble("max_speed"));
                            accumulator.pointCount += geometry.size();

                        } catch (ParseException e) {
                            log.error("Error parsing geometry: {}", e.getMessage());
                        }
                    }
                    trackCount++;
                }

                // 시간 범위 계산
//                long rangeMinutes = java.time.Duration.between(range.getStart(), range.getEnd()).toMinutes();
//                int expectedBuckets = (int) Math.ceil(rangeMinutes / 5.0);

//                log.info("[{}] Range [{} - {}] = {} minutes, {} buckets expected",
//                    tableName, range.getStart(), range.getEnd(), rangeMinutes, expectedBuckets);
//                log.info("[{}] Processed {} tracks for {} unique vessels (avg {:.1f} tracks/vessel)",
//                    tableName, trackCount, vesselCount, vesselCount > 0 ? (double)trackCount/vesselCount : 0);
//                log.debug("[{}] Vessel count in this chunk: {} (cumulative in vesselMap: {})",
//                    tableName, vesselCount, vesselMap.size());
            }
        } catch (SQLException e) {
            log.error("Error processing table range: {}", e.getMessage());
        }

        // VesselAccumulator를 CompactVesselTrack로 변환
        return vesselMap.entrySet().stream()
                .map(entry -> {
                    String vesselId = entry.getKey();
                    VesselAccumulator acc = entry.getValue();

                    // 평균속도 계산 (전체 궤적 기반)
                    double avgSpeed = 0.0;
                    if (acc.totalDistance > 0 && acc.timestamps.size() > 1) {
                        String firstTs = acc.timestamps.get(0);
                        String lastTs = acc.timestamps.get(acc.timestamps.size() - 1);
                        
                        LocalDateTime startTime;
                        LocalDateTime endTime;
                        
                        // Unix timestamp 감지 (10자리 이상 숫자)
                        if (firstTs.matches("\\d{10,}")) {
                            startTime = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(Long.parseLong(firstTs)), 
                                java.time.ZoneId.systemDefault());
                            endTime = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(Long.parseLong(lastTs)), 
                                java.time.ZoneId.systemDefault());
                        } else {
                            startTime = LocalDateTime.parse(firstTs, TIMESTAMP_FORMATTER);
                            endTime = LocalDateTime.parse(lastTs, TIMESTAMP_FORMATTER);
                        }
                        
                        double hours = Duration.between(startTime, endTime).toMinutes() / 60.0;
                        if (hours > 0) {
                            avgSpeed = acc.totalDistance / hours;
                        }
                    }

                    return CompactVesselTrack.builder()
                            .vesselId(vesselId)
                            .sigSrcCd(acc.sigSrcCd)
                            .targetId(acc.targetId)
                            .shipName(acc.shipName)  // 선명 추가
                            .shipType(acc.shipType)  // 선종 추가
                            .shipKindCode(acc.shipKindCode)  // 선박 종류 코드 추가
                            .geometry(acc.geometry)
                            .timestamps(acc.timestamps)
                            .speeds(acc.speeds)
                            .totalDistance(acc.totalDistance)
                            .avgSpeed(avgSpeed)
                            .maxSpeed(acc.maxSpeed)
                            .pointCount(acc.pointCount)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 쿼리를 테이블 전략별로 처리
     */
    public List<TrackChunkResponse> processQueryInChunks(TrackQueryRequest request, String queryId) {
        log.info("Processing chunked query: {}", queryId);
        queryStartTime = System.currentTimeMillis();
        processedTimeRanges.clear();

        // 시간 범위별 테이블 전략 분할
        Map<TableStrategy, List<TimeRange>> strategyMap = splitTimeRangeByStrategy(
                request.getStartTime(), request.getEndTime()
        );

        log.info("Query {} using strategies: {}", queryId, strategyMap.keySet());

        List<TrackChunkResponse> responses = new ArrayList<>();
        int globalChunkIndex = 0;
        Set<String> uniqueVesselIds = new HashSet<>();

        // 테이블 전략별 처리 (daily → hourly → 5min 순서)
        for (TableStrategy strategy : new TableStrategy[]{TableStrategy.DAILY, TableStrategy.HOURLY, TableStrategy.FIVE_MINUTE}) {
            if (!strategyMap.containsKey(strategy)) continue;

            List<TimeRange> ranges = strategyMap.get(strategy);
            log.info("Processing {} strategy with {} ranges", strategy, ranges.size());

            // Daily 테이블의 경우 범위별로 처리 (청크 분할 최소화)
            if (strategy == TableStrategy.DAILY) {
                for (TimeRange range : ranges) {
                    try {
                        List<CompactVesselTrack> compactTracks = processTableRange(request, strategy, range);

                        if (!compactTracks.isEmpty()) {
                            // 메시지 크기로 분할
                            List<List<CompactVesselTrack>> batches = splitByMessageSize(compactTracks);
                            for (List<CompactVesselTrack> batch : batches) {
                                TrackChunkResponse response = new TrackChunkResponse();
                                response.setQueryId(queryId);
                                response.setChunkIndex(globalChunkIndex++);
                                response.setIsLastChunk(false); // 나중에 설정
                                response.setTotalChunks(-1); // 마지막 청크에서 설정
                                response.setCompactTracks(batch);
                                // 처리된 시간 계산
                                String rangeKey = "daily_" + range.getStart();
                                processedTimeRanges.put(rangeKey, (int)Duration.between(range.getStart(), range.getEnd()).toMinutes());
                                int processedMinutes = processedTimeRanges.values().stream().mapToInt(Integer::intValue).sum();
                                response.setStats(createChunkStats(batch, uniqueVesselIds, processedMinutes));
                                responses.add(response);

                                // 유니크 선박 추가
                                batch.forEach(track -> uniqueVesselIds.add(track.getVesselId()));

                                // 배치 크기 로그 (Daily에도 추가)
                                int batchSize = batch.stream().mapToInt(t -> estimateTrackSize(t)).sum() / 1024;
                                log.debug("[{}] Batch {} size: {}KB, tracks: {}",
                                        strategy, response.getChunkIndex(), batchSize, batch.size());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error processing daily range {}: {}", range, e.getMessage());
                    }
                }
            } else {
                // Hourly/5min 테이블은 청크로 분할
                List<TimeChunk> chunks = divideRangesIntoChunks(ranges, strategy);

                for (TimeChunk chunk : chunks) {
                    try {
                        List<CompactVesselTrack> compactTracks = processTableRange(request, strategy,
                                new TimeRange(chunk.start, chunk.end));

                        if (!compactTracks.isEmpty()) {
                            // 메시지 크기로 분할 (5min/hourly에도 적용)
                            List<List<CompactVesselTrack>> batches = splitByMessageSize(compactTracks);
                            for (List<CompactVesselTrack> batch : batches) {
                                TrackChunkResponse response = new TrackChunkResponse();
                                response.setQueryId(queryId);
                                response.setChunkIndex(globalChunkIndex++);
                                response.setIsLastChunk(false);
                                response.setTotalChunks(-1); // 마짉 청크에서 설정
                                response.setCompactTracks(batch);
                                // 처리된 시간 업데이트
                                String chunkKey = strategy + "_" + chunk.start;
                                processedTimeRanges.put(chunkKey, (int)Duration.between(chunk.start, chunk.end).toMinutes());
                                int processedMin = processedTimeRanges.values().stream().mapToInt(Integer::intValue).sum();
                                response.setStats(createChunkStats(batch, uniqueVesselIds, processedMin));
                                responses.add(response);

                                // 유니크 선박 추가
                                batch.forEach(track -> uniqueVesselIds.add(track.getVesselId()));

                                // 배치 크기 로그
                                int batchSize = batch.stream().mapToInt(t -> estimateTrackSize(t)).sum() / 1024;
                                log.debug("[{}] Batch {} size: {}KB, tracks: {}",
                                        strategy, response.getChunkIndex(), batchSize, batch.size());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error processing chunk {}: {}", chunk, e.getMessage());
                    }
                }
            }
        }

        // 마지막 청크 표시
        if (!responses.isEmpty()) {
            responses.get(responses.size() - 1).setIsLastChunk(true);

            // 총 선박 수 계산
            responses.forEach(response -> {
                if (response.getCompactTracks() != null) {
                    response.getCompactTracks().forEach(track -> uniqueVesselIds.add(track.getVesselId()));
                }
            });
            log.info("Query {} completed: Total {} chunks, {} unique vessels processed",
                    queryId, responses.size(), uniqueVesselIds.size());
        }

        // 전체 응답 크기 체크
        long totalResponseSize = responses.stream()
                .mapToLong(r -> r.getCompactTracks().stream()
                        .mapToInt(t -> estimateTrackSize(t))
                        .sum())
                .sum();

        log.info("Query {} total response size: {} MB across {} chunks",
                queryId, totalResponseSize / (1024 * 1024), responses.size());

        return responses;
    }

    /**
     * 비동기 스트리밍 메서드 (컨트롤러에서 호출)
     */
    @Async("trackStreamingExecutor")
    public void streamChunkedTracks(TrackQueryRequest request,
                                    String queryId,
                                    Consumer<TrackChunkResponse> chunkConsumer,
                                    Consumer<QueryStatusUpdate> statusConsumer) {
        try {
            log.info("Starting chunked streaming for query: {}", queryId);
            queryStartTime = System.currentTimeMillis();
            processedTimeRanges.clear();

            // 백프레셔 메트릭스 초기화
            BackpressureMetrics metrics = new BackpressureMetrics();
            queryMetrics.put(queryId, metrics);
            pendingBufferSize.set(0);

            log.info("[BACKPRESSURE] Query {} initialized - Buffer size: 0, Max buffer: {}MB",
                    queryId, MAX_PENDING_BUFFER / (1024 * 1024));

            // 시간 범위별 테이블 전략 분할
            Map<TableStrategy, List<TimeRange>> strategyMap = splitTimeRangeByStrategy(
                    request.getStartTime(), request.getEndTime()
            );

            // 전체 시간 계산
            estimatedTotalMinutes = (int)Duration.between(request.getStartTime(), request.getEndTime()).toMinutes();
            log.info("Total time range: {} minutes", estimatedTotalMinutes);

            int globalChunkIndex = 0;
            int totalVessels = 0;
            Set<String> uniqueVesselIds = new HashSet<>();

            // 테이블 전략별 처리 (daily → hourly → 5min 순서)
            for (TableStrategy strategy : new TableStrategy[]{TableStrategy.DAILY, TableStrategy.HOURLY, TableStrategy.FIVE_MINUTE}) {
                if (!strategyMap.containsKey(strategy)) continue;

                List<TimeRange> ranges = strategyMap.get(strategy);
                log.info("Processing {} strategy with {} ranges", strategy, ranges.size());

                if (strategy == TableStrategy.DAILY) {
                    // Daily는 기존 방식 유지 (이미 일 단위)
                    processDailyStrategy(ranges, request, queryId, chunkConsumer, statusConsumer,
                            globalChunkIndex, uniqueVesselIds);
                    globalChunkIndex = getCurrentChunkIndex();
                } else {
                    // Hourly/5min은 6시간 단위로 그룹화하여 처리
                    Map<String, List<TimeRange>> timeGroups = groupRangesByTimeWindow(ranges, 6);

                    for (Map.Entry<String, List<TimeRange>> groupEntry : timeGroups.entrySet()) {
                        String groupKey = groupEntry.getKey();
                        List<TimeRange> groupRanges = groupEntry.getValue();
                        log.info("[{}] Processing time window {} with {} ranges", strategy, groupKey, groupRanges.size());

                        // 시간 그룹 데이터를 병합
                        Map<String, VesselAccumulator> mergedMap = new HashMap<>(20000);
                        LocalDateTime baseTime = null;

                        for (TimeRange range : groupRanges) {
                            try {
                                // 첫 범위의 시작 시간을 기준으로 설정
                                if (baseTime == null) {
                                    baseTime = range.getStart();
                                }

                                List<CompactVesselTrack> compactTracks = processTableRangeWithBaseTime(
                                        request, strategy, range, baseTime);

                                // 선박별로 볕합
                                for (CompactVesselTrack track : compactTracks) {
                                    String vesselId = track.getVesselId();
                                    VesselAccumulator accumulator = mergedMap.get(vesselId);

                                    if (accumulator == null) {
                                        accumulator = new VesselAccumulator();
                                        accumulator.sigSrcCd = track.getSigSrcCd();
                                        accumulator.targetId = track.getTargetId();

                                        // 선박 정보 조회 (캐시 우선) - 추가
                                        VesselInfo vesselInfo = getVesselInfo(track.getSigSrcCd(), track.getTargetId());
                                        accumulator.shipName = vesselInfo.shipName;
                                        accumulator.shipType = vesselInfo.shipType;

                                        // shipKindCode 계산
                                        accumulator.shipKindCode = ShipKindCodeConverter.getShipKindCode(track.getSigSrcCd(), vesselInfo.shipType);

                                        mergedMap.put(vesselId, accumulator);
                                    }

                                    // 데이터 병합
                                    accumulator.geometry.addAll(track.getGeometry());
                                    accumulator.timestamps.addAll(track.getTimestamps());
                                    accumulator.speeds.addAll(track.getSpeeds());
                                    accumulator.totalDistance += track.getTotalDistance();
                                    accumulator.maxSpeed = Math.max(accumulator.maxSpeed, track.getMaxSpeed());
                                    accumulator.pointCount += track.getPointCount();
                                }
                            } catch (Exception e) {
                                log.error("Error processing {} range {}: {}", strategy, range, e.getMessage());
                            }
                        }

                        // 병합된 데이터를 청크로 분할하여 전송
                        if (!mergedMap.isEmpty()) {
                            List<CompactVesselTrack> mergedTracks = mergedMap.entrySet().stream()
                                    .map(entry -> {
                                        String vesselId = entry.getKey();
                                        VesselAccumulator acc = entry.getValue();

                                        double avgSpeed = 0.0;
                                        if (acc.totalDistance > 0 && acc.timestamps.size() > 1) {
                                            String firstTs = acc.timestamps.get(0);
                                            String lastTs = acc.timestamps.get(acc.timestamps.size() - 1);
                                            
                                            LocalDateTime start;
                                            LocalDateTime end;
                                            
                                            // Unix timestamp 감지 (10자리 이상 숫자)
                                            if (firstTs.matches("\\d{10,}")) {
                                                start = LocalDateTime.ofInstant(
                                                    java.time.Instant.ofEpochSecond(Long.parseLong(firstTs)), 
                                                    java.time.ZoneId.systemDefault());
                                                end = LocalDateTime.ofInstant(
                                                    java.time.Instant.ofEpochSecond(Long.parseLong(lastTs)), 
                                                    java.time.ZoneId.systemDefault());
                                            } else {
                                                start = LocalDateTime.parse(firstTs, TIMESTAMP_FORMATTER);
                                                end = LocalDateTime.parse(lastTs, TIMESTAMP_FORMATTER);
                                            }
                                            
                                            double hours = Duration.between(start, end).toMinutes() / 60.0;
                                            if (hours > 0) {
                                                avgSpeed = acc.totalDistance / hours;
                                            }
                                        }

                                        return CompactVesselTrack.builder()
                                                .vesselId(vesselId)
                                                .sigSrcCd(acc.sigSrcCd)
                                                .targetId(acc.targetId)
                                                .shipName(acc.shipName)  // 선명 추가
                                                .shipType(acc.shipType)  // 선종 추가
                                                .shipKindCode(acc.shipKindCode)  // 선박 종류 코드 추가
                                                .geometry(acc.geometry)
                                                .timestamps(acc.timestamps)
                                                .speeds(acc.speeds)
                                                .totalDistance(acc.totalDistance)
                                                .avgSpeed(avgSpeed)
                                                .maxSpeed(acc.maxSpeed)
                                                .pointCount(acc.pointCount)
                                                .build();
                                    })
                                    .collect(Collectors.toList());

                            // 전체 포인트 통계 계산
                            int totalOriginalPoints = mergedTracks.stream()
                                    .mapToInt(t -> t.getPointCount())
                                    .sum();

                            log.info("[{}] Time window {} - Merged {} vessels, Total {} points",
                                    strategy, groupKey, mergedTracks.size(), totalOriginalPoints);

                            List<List<CompactVesselTrack>> batches = splitByMessageSize(mergedTracks, queryId);

                            for (List<CompactVesselTrack> batch : batches) {
                                TrackChunkResponse response = new TrackChunkResponse();
                                response.setQueryId(queryId);
                                response.setChunkIndex(globalChunkIndex++);
                                response.setIsLastChunk(false);
                                response.setTotalChunks(-1); // 마짉 청크에서 설정
                                response.setCompactTracks(batch);
                                // 처리된 시간 추가
                                String timeKey = groupKey + "_" + strategy;
                                processedTimeRanges.put(timeKey, groupRanges.stream()
                                        .mapToInt(r -> (int)Duration.between(r.getStart(), r.getEnd()).toMinutes())
                                        .sum());
                                int currentProcessedMin = processedTimeRanges.values().stream().mapToInt(Integer::intValue).sum();
                                response.setStats(createChunkStats(batch, uniqueVesselIds, currentProcessedMin));

                                // 버퍼 크기 계산 및 추가
                                int chunkSize = batch.stream().mapToInt(t -> estimateTrackSize(t)).sum();
                                long currentBufferSize = pendingBufferSize.addAndGet(chunkSize);
                                metrics.totalBytes.addAndGet(chunkSize);
                                metrics.chunkCount.incrementAndGet();

                                // 버퍼 사용률 로그
                                double bufferUsage = (double) currentBufferSize / MAX_PENDING_BUFFER * 100;
                                if (bufferUsage > 80 && System.currentTimeMillis() - metrics.lastWarningTime > 5000) {
                                    metrics.bufferWarnings.incrementAndGet();
                                    metrics.lastWarningTime = System.currentTimeMillis();
                                    log.warn("[BACKPRESSURE] Query {} - Buffer usage high: {}% ({} MB / {} MB)",
                                            queryId, String.format("%.1f", bufferUsage), currentBufferSize / (1024 * 1024),
                                            MAX_PENDING_BUFFER / (1024 * 1024));
                                }

                                // 버퍼가 가듍 찬 경우 대기 및 동적 청크 크기 조절
                                int backpressureWaitCount = 0;
                                while (pendingBufferSize.get() > MAX_PENDING_BUFFER) {
                                    if (backpressureWaitCount == 0) {
                                        metrics.backpressureEvents.incrementAndGet();
                                        log.warn("[BACKPRESSURE] Query {} - Buffer full! Waiting for buffer to drain. Current: {} MB",
                                                queryId, pendingBufferSize.get() / (1024 * 1024));
                                    }
                                    Thread.sleep(50);
                                    backpressureWaitCount++;

                                    // 500ms 이상 대기 시 청크 크기 감소
                                    if (backpressureWaitCount > 10 && metrics.dynamicChunkSizeKB > MIN_MESSAGE_SIZE_KB) {
                                        int oldSize = metrics.dynamicChunkSizeKB;
                                        metrics.dynamicChunkSizeKB = Math.max(MIN_MESSAGE_SIZE_KB,
                                                metrics.dynamicChunkSizeKB - 512);
                                        log.info("[BACKPRESSURE] Query {} - Reducing chunk size: {} KB -> {} KB",
                                                queryId, oldSize, metrics.dynamicChunkSizeKB);
                                    }
                                }

                                if (backpressureWaitCount > 0) {
                                    log.info("[BACKPRESSURE] Query {} - Buffer drained after {} ms wait",
                                            queryId, backpressureWaitCount * 50);
                                }

                                // 즉시 전송
                                chunkConsumer.accept(response);

                                // 전송 완료 후 버퍼 크기 감소 (비동기 처리 고려)
                                CompletableFuture.runAsync(() -> {
                                    try {
                                        Thread.sleep(100); // 네트워크 전송 시간 고려
                                        pendingBufferSize.addAndGet(-chunkSize);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });

                                // 유니크 선박 카운트
                                batch.forEach(track -> uniqueVesselIds.add(track.getVesselId()));

                                // 진행률 업데이트 (시간 기반만 사용)
                                double timeProgress = (double) currentProcessedMin / estimatedTotalMinutes * 100;

                                statusConsumer.accept(new QueryStatusUpdate(
                                        queryId,
                                        "PROCESSING",
                                        "Processing chunk " + globalChunkIndex,
                                        Math.min(99.0, timeProgress)
                                ));

                                // 버퍼 사용률에 따른 동적 대기
                                long currentBuffer = pendingBufferSize.get();
                                int waitTime = currentBuffer > 30_000_000 ? 100 :
                                        currentBuffer > 10_000_000 ? 50 : 10;
                                Thread.sleep(waitTime);

                                // 진행 상황 로그 (매 10번째 청크마다)
                                if (globalChunkIndex % 10 == 0) {
                                    log.info("Progress: chunk {}, vessels: {}, time progress: {}%",
                                            globalChunkIndex, uniqueVesselIds.size(),
                                            Math.round(timeProgress));
                                }
                            }
                        }
                    }
                }
            }

            // 마지막 청크 표시
            if (globalChunkIndex > 0) {
                // 마지막 청크 재전송 (마지막 플래그 설정)
                TrackChunkResponse lastChunkMarker = new TrackChunkResponse();
                lastChunkMarker.setQueryId(queryId);
                lastChunkMarker.setChunkIndex(globalChunkIndex - 1);
                lastChunkMarker.setTotalChunks(globalChunkIndex);
                lastChunkMarker.setIsLastChunk(true);
                lastChunkMarker.setCompactTracks(new ArrayList<>());
                int finalProcessedMin = processedTimeRanges.values().stream().mapToInt(Integer::intValue).sum();
                lastChunkMarker.setStats(createChunkStats(new ArrayList<>(), uniqueVesselIds, finalProcessedMin));
                chunkConsumer.accept(lastChunkMarker);
            }

            log.info("Query {} completed: {} chunks, {} unique vessels",
                    queryId, globalChunkIndex, uniqueVesselIds.size());

            // 백프레셔 통계 출력
            if (metrics != null) {
                log.info("[BACKPRESSURE] Query {} statistics:", queryId);
                log.info("[BACKPRESSURE] - Total bytes sent: {} MB", metrics.totalBytes.get() / (1024 * 1024));
                log.info("[BACKPRESSURE] - Total chunks: {}", metrics.chunkCount.get());
                log.info("[BACKPRESSURE] - Buffer warnings: {}", metrics.bufferWarnings.get());
                log.info("[BACKPRESSURE] - Backpressure events: {}", metrics.backpressureEvents.get());
                log.info("[BACKPRESSURE] - Final chunk size: {} KB (original: {} KB)",
                        metrics.dynamicChunkSizeKB, MAX_MESSAGE_SIZE_KB);

                if (metrics.chunkCount.get() > 0) {
                    double avgChunkSize = (double) metrics.totalBytes.get() / metrics.chunkCount.get() / 1024;
                    log.info("[BACKPRESSURE] - Average chunk size: {} KB", String.format("%.1f", avgChunkSize));
                }
            }

            // 완료 상태
            statusConsumer.accept(new QueryStatusUpdate(
                    queryId,
                    "COMPLETED",
                    "Query completed successfully",
                    100.0
            ));

        } catch (Exception e) {
            log.error("Error in chunked streaming: {}", e.getMessage(), e);

            // 에러 시에도 백프레셔 통계 출력
            BackpressureMetrics errorMetrics = queryMetrics.get(queryId);
            if (errorMetrics != null) {
                log.error("[BACKPRESSURE] Query {} failed with statistics:", queryId);
                log.error("[BACKPRESSURE] - Chunks sent before error: {}", errorMetrics.chunkCount.get());
                log.error("[BACKPRESSURE] - Backpressure events: {}", errorMetrics.backpressureEvents.get());
            }

            statusConsumer.accept(new QueryStatusUpdate(
                    queryId,
                    "ERROR",
                    "Error: " + e.getMessage(),
                    0.0
            ));
        } finally {
            // 쿼리 메트릭스 정리
            cleanupQueryMetrics(queryId);
        }
    }

    /**
     * 쿼리 취소
     */
    public void cancelQuery(String queryId) {
        log.info("Cancelling chunked query: {}", queryId);
        // TODO: 실제 취소 로직 구현
        cleanupQueryMetrics(queryId);
    }

    /**
     * 쿼리 메트릭스 정리
     */
    private void cleanupQueryMetrics(String queryId) {
        BackpressureMetrics removed = queryMetrics.remove(queryId);
        if (removed != null) {
            log.debug("[BACKPRESSURE] Cleaned up metrics for query {}", queryId);
        }
    }

    /**
     * 메시지 크기 기반으로 트랙 분할 (동적 크기 적용)
     */
    private List<List<CompactVesselTrack>> splitByMessageSize(List<CompactVesselTrack> tracks) {
        return splitByMessageSize(tracks, null);
    }

    private List<List<CompactVesselTrack>> splitByMessageSize(List<CompactVesselTrack> tracks, String queryId) {
        // 쿼리에 대한 동적 청크 크기 가져오기
        int maxChunkSizeKB = MAX_MESSAGE_SIZE_KB;
        if (queryId != null && queryMetrics.containsKey(queryId)) {
            maxChunkSizeKB = queryMetrics.get(queryId).dynamicChunkSizeKB;
        }

        List<List<CompactVesselTrack>> batches = new ArrayList<>();
        List<CompactVesselTrack> currentBatch = new ArrayList<>();
        int currentSize = 0;

        for (CompactVesselTrack track : tracks) {
            int trackSize = estimateTrackSize(track);

            // 현재 배치가 크기 제한을 초과하고 비어있지 않으면 새 배치 시작
            if (currentSize + trackSize > maxChunkSizeKB * 1024 && !currentBatch.isEmpty()) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                currentSize = 0;
            }

            currentBatch.add(track);
            currentSize += trackSize;
        }

        // 마지막 배치 추가
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        log.info("[splitByMessageSize] {} tracks split into {} batches (max size: {}KB)",
                tracks.size(), batches.size(), maxChunkSizeKB);

        return batches;
    }

    /**
     * 트랙 크기 추정 (바이트)
     */
    private int estimateTrackSize(CompactVesselTrack track) {
        // 각 좌표: 2 * 8 bytes (double), 타임스탬프: ~20 chars, 속도: 8 bytes
        int pointSize = 16 + 20 + 8; // ~44 bytes per point
        int metadataSize = 100; // vessel ID, 기타 필드
        return track.getPointCount() * pointSize + metadataSize;
    }

    /**
     * 두 좌표 사이의 거리 계산 (Haversine 공식, 해리)
     */
    private double calculateDistance(Coordinate from, Coordinate to) {
        double lat1 = Math.toRadians(from.y);
        double lat2 = Math.toRadians(to.y);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to.x - from.x);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // 지구 반지름 (nm)
        double R = 3440.065; // nautical miles
        return R * c;
    }

    /**
     * 두 좌표 사이의 거리 계산 (double 배열 버전)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = lat2Rad - lat1Rad;
        double deltaLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double R = 3440.065; // nautical miles
        return R * c;
    }

    /**
     * 시간 범위별 테이블 선택 로직 (StompTrackStreamingService 참고)
     */
    private Map<TableStrategy, List<TimeRange>> splitTimeRangeByStrategy(LocalDateTime start, LocalDateTime end) {
        Map<TableStrategy, List<TimeRange>> strategyMap = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

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
                // 24시간 이전 - 일 단위로 처리
                // 일별 테이블은 전체 날짜를 포함해야 함
                LocalDateTime dayStart = current.toLocalDate().atStartOfDay();
                LocalDateTime dayEnd = dayStart.plusDays(1);

                // 요청된 시간 범위를 초과하지 않도록 조정
                if (dayEnd.isAfter(end)) {
                    dayEnd = end.toLocalDate().plusDays(1).atStartOfDay();
                }

                boolean alreadyAdded = dailyRanges.stream()
                        .anyMatch(r -> r.getStart().toLocalDate().equals(dayStart.toLocalDate()));

                if (!alreadyAdded && dayStart.isBefore(end)) {
                    dailyRanges.add(new TimeRange(dayStart, dayEnd));
                }

                rangeEnd = dayEnd;
            }

            current = rangeEnd;
        }

        // 결과를 strategyMap에 추가 (데이터 존재 여부 확인 후)
        if (!dailyRanges.isEmpty()) {
            List<TimeRange> mergedDailyRanges = mergeDailyRanges(dailyRanges);
            List<TimeRange> validDailyRanges = new ArrayList<>();
            List<TimeRange> fallbackToHourly = new ArrayList<>();

            // 각 일별 범위에 대해 데이터 존재 여부 확인
            for (TimeRange dailyRange : mergedDailyRanges) {
                if (hasDataInTable(TableStrategy.DAILY.getTableName(), dailyRange.getStart(), dailyRange.getEnd())) {
                    validDailyRanges.add(dailyRange);
                    log.debug("Daily data found for range: {}", dailyRange);
                } else {
                    log.info("No daily data for range {}, falling back to hourly", dailyRange);
                    LocalDateTime hourlyStart = dailyRange.getStart();
                    while (hourlyStart.isBefore(dailyRange.getEnd())) {
                        LocalDateTime rangeEnd = hourlyStart.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                        if (rangeEnd.isAfter(dailyRange.getEnd())) rangeEnd = dailyRange.getEnd();
                        fallbackToHourly.add(new TimeRange(hourlyStart, rangeEnd));
                        hourlyStart = rangeEnd;
                    }
                }
            }

            if (!validDailyRanges.isEmpty()) {
                strategyMap.put(TableStrategy.DAILY, validDailyRanges);
            }
            hourlyRanges.addAll(fallbackToHourly);
        }

        if (!hourlyRanges.isEmpty()) {
            List<TimeRange> validHourlyRanges = new ArrayList<>();
            List<TimeRange> fallbackTo5Min = new ArrayList<>();

            for (TimeRange range : hourlyRanges) {
                if (hasDataInTable(TableStrategy.HOURLY.getTableName(), range.getStart(), range.getEnd())) {
                    validHourlyRanges.add(range);
                } else {
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

        return strategyMap;
    }

    /**
     * 테이블에 데이터가 존재하는지 확인
     */
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

    /**
     * 일별 범위를 날짜별로 병합
     */
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

    /**
     * 범위를 청크로 분할
     */
    private List<TimeChunk> divideRangesIntoChunks(List<TimeRange> ranges, TableStrategy strategy) {
        List<TimeChunk> chunks = new ArrayList<>();

        for (TimeRange range : ranges) {
            if (strategy == TableStrategy.HOURLY) {
                // Hourly는 3시간 단위로 분할
                LocalDateTime chunkStart = range.getStart();
                while (chunkStart.isBefore(range.getEnd())) {
                    LocalDateTime chunkEnd = chunkStart.plusHours(3);
                    if (chunkEnd.isAfter(range.getEnd())) {
                        chunkEnd = range.getEnd();
                    }
                    chunks.add(new TimeChunk(chunkStart, chunkEnd));
                    chunkStart = chunkEnd;
                }
            } else {
                // 5min은 30분 단위로 분할
                LocalDateTime chunkStart = range.getStart();
                while (chunkStart.isBefore(range.getEnd())) {
                    LocalDateTime chunkEnd = chunkStart.plusMinutes(30);
                    if (chunkEnd.isAfter(range.getEnd())) {
                        chunkEnd = range.getEnd();
                    }
                    chunks.add(new TimeChunk(chunkStart, chunkEnd));
                    chunkStart = chunkEnd;
                }
            }
        }

        return chunks;
    }

    /**
     * 청크 처리 - 시간 범위별 테이블 전략 사용
     */
    private List<CompactVesselTrack> processChunk(TrackQueryRequest request, TimeChunk chunk) {
        Map<String, CompactVesselTrack.CompactVesselTrackBuilder> vesselMap = new HashMap<>();

        // 시간 범위별 테이블 전략 분할
        Map<TableStrategy, List<TimeRange>> strategyMap = splitTimeRangeByStrategy(
                chunk.start, chunk.end
        );

        // 각 테이블 전략별로 처리
        for (Map.Entry<TableStrategy, List<TimeRange>> entry : strategyMap.entrySet()) {
            TableStrategy strategy = entry.getKey();
            List<TimeRange> ranges = entry.getValue();

            for (TimeRange range : ranges) {
                List<CompactVesselTrack> tracks = processTableRange(request, strategy, range);
                // 선박별로 병합
                for (CompactVesselTrack track : tracks) {
                    CompactVesselTrack.CompactVesselTrackBuilder builder = vesselMap.get(track.getVesselId());
                    if (builder == null) {
                        builder = CompactVesselTrack.builder()
                                .vesselId(track.getVesselId())
                                .sigSrcCd(track.getSigSrcCd())
                                .targetId(track.getTargetId())
                                .geometry(new ArrayList<>())
                                .timestamps(new ArrayList<>())
                                .speeds(new ArrayList<>())
                                .totalDistance(0.0)
                                .avgSpeed(0.0)
                                .maxSpeed(0.0)
                                .pointCount(0);
                        vesselMap.put(track.getVesselId(), builder);
                    }

                    // 데이터 병합
                    builder.geometry(mergeGeometry(builder.build().getGeometry(), track.getGeometry()));
                    builder.timestamps(mergeTimestamps(builder.build().getTimestamps(), track.getTimestamps()));
                    builder.speeds(mergeSpeeds(builder.build().getSpeeds(), track.getSpeeds()));
                    builder.totalDistance(builder.build().getTotalDistance() + track.getTotalDistance());
                    builder.maxSpeed(Math.max(builder.build().getMaxSpeed(), track.getMaxSpeed()));
                    builder.pointCount(builder.build().getPointCount() + track.getPointCount());
                }
            }
        }

        // 평균 속도 계산 및 최종 변환
        return vesselMap.values().stream()
                .map(builder -> {
                    CompactVesselTrack track = builder.build();
                    if (track.getTotalDistance() > 0 && track.getTimestamps().size() > 1) {
                        // Unix timestamp 지원
                        String firstTs = track.getTimestamps().get(0);
                        String lastTs = track.getTimestamps().get(track.getTimestamps().size() - 1);
                        
                        LocalDateTime startTime;
                        LocalDateTime endTime;
                        
                        // Unix timestamp 감지 (10자리 이상 숫자)
                        if (firstTs.matches("\\d{10,}")) {
                            startTime = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(Long.parseLong(firstTs)), 
                                java.time.ZoneId.systemDefault());
                            endTime = LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(Long.parseLong(lastTs)), 
                                java.time.ZoneId.systemDefault());
                        } else {
                            startTime = LocalDateTime.parse(firstTs, TIMESTAMP_FORMATTER);
                            endTime = LocalDateTime.parse(lastTs, TIMESTAMP_FORMATTER);
                        }
                        
                        double hours = java.time.Duration.between(startTime, endTime).toMinutes() / 60.0;
                        if (hours > 0) {
                            track.setAvgSpeed(track.getTotalDistance() / hours);
                        }
                    }
                    return track;
                })
                .collect(Collectors.toList());
    }


    /**
     * 테이블 선택 (집계 지연 고려)
     */
    private String selectTableByTimeRange(LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now();

        // Daily 데이터는 매일 01:00에 집계 (전일 데이터)
        LocalDateTime lastDailyAggregation = now.toLocalDate().atTime(1, 0);
        if (now.isBefore(lastDailyAggregation)) {
            lastDailyAggregation = lastDailyAggregation.minusDays(1);
        }

        // Hourly 데이터는 매시 10분에 집계 (이전 시간 데이터)
        LocalDateTime lastHourlyAggregation = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(10);
        if (now.isBefore(lastHourlyAggregation)) {
            lastHourlyAggregation = lastHourlyAggregation.minusHours(1);
        }

        // 조회 시간이 완전히 daily 집계 완료 범위에 있으면
        if (end.isBefore(lastDailyAggregation.minusDays(1))) {
            return "signal.t_vessel_tracks_daily";
        }

        // 조회 시간이 완전히 hourly 집계 완료 범위에 있고 1일 이상이면
        if (end.isBefore(lastHourlyAggregation.minusHours(1)) &&
                java.time.Duration.between(start, end).toHours() >= 24) {
            return "signal.t_vessel_tracks_daily";
        }

        // 조회 시간이 완전히 hourly 집계 완료 범위에 있으면
        if (end.isBefore(lastHourlyAggregation.minusHours(1))) {
            return "signal.t_vessel_tracks_hourly";
        }

        // 그 외의 경우 5분 데이터 사용
        return "signal.t_vessel_tracks_5min";
    }

    /**
     * 쿼리 생성 (간소화 적용)
     */
    private String buildRangeQuery(String tableName, TrackQueryRequest request, TimeRange range, SimplificationLevel simplificationLevel) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT sig_src_cd, target_id, time_bucket, ");

        // track_geom 고정 사용
        
        // 간소화 적용
        if (simplificationLevel != SimplificationLevel.NONE && simplificationLevel.getTolerance() > 0) {
            sql.append("ST_AsText(ST_Simplify(track_geom, ").append(simplificationLevel.getTolerance())
                    .append(")) as track_geom, ");
        } else {
            sql.append("ST_AsText(track_geom) as track_geom, ");
        }

        sql.append("distance_nm, avg_speed, max_speed, point_count");

        // start_position, end_position에서 시간 정보 추출 (가능한 경우)
        if (!tableName.contains("5min")) {
            // hourly, daily 테이블에는 start_position, end_position이 있음
            sql.append(", start_position->>'time' as start_time");
            sql.append(", end_position->>'time' as end_time");
        }

        sql.append(" FROM ").append(tableName).append(" ");
        sql.append("WHERE time_bucket >= ? ");
        sql.append("AND time_bucket < ? ");

        // Viewport 필터 - 파라미터 바인딩 사용
        if (request.getViewport() != null) {
            sql.append("AND ST_Intersects(track_geom, ST_MakeEnvelope(?, ?, ?, ?, 4326)) ");
        }

        // 거리/속도 필터
        if (request.getMinAvgSpeed() != null) {
            sql.append("AND avg_speed >= ").append(request.getMinAvgSpeed()).append(" ");
        }
        if (request.getMaxAvgSpeed() != null) {
            sql.append("AND avg_speed <= ").append(request.getMaxAvgSpeed()).append(" ");
        }

        // 정렬 및 제한
        sql.append("ORDER BY sig_src_cd, target_id, time_bucket ");
        sql.append("LIMIT ").append(MAX_TRACKS_PER_CHUNK);

        return sql.toString();
    }

    /**
     * 간소화 레벨 결정
     */
    private SimplificationLevel determineSimplificationLevel(TrackQueryRequest request, TimeChunk chunk) {
        // 줌 레벨 우선 적용
        Integer zoom = request.getZoomLevel();
        if (zoom == null) zoom = 10; // 기본값

        // 줌 레벨에 따른 간소화 레벨 결정 (가장 우선순위가 높음)
        if (zoom < 6) {
            return SimplificationLevel.EXTREME;      // 원거리: 90% 제거
        } else if (zoom < 8) {
            return SimplificationLevel.VERY_HEAVY;   // 80% 제거
        } else if (zoom < 10) {
            return SimplificationLevel.HEAVY;        // 70% 제거
        } else if (zoom < 12) {
            return SimplificationLevel.MODERATE;     // 50% 제거
        }

        // 시간 범위 계산 (보조 기준)
        Duration duration = Duration.between(chunk.start, chunk.end);
        long hours = duration.toHours();

        // 뷰포트 크기 계산
        double viewportArea = 180 * 90; // 기본값 (전체)
        if (request.getViewport() != null) {
            ViewportFilter vp = request.getViewport();
            double width = vp.getMaxLon() - vp.getMinLon();
            double height = vp.getMaxLat() - vp.getMinLat();
            viewportArea = width * height;
        }

        // 간소화 모드 확인
        if (request.getSimplificationMode() != null) {
            if ("NONE".equals(request.getSimplificationMode())) {
                return SimplificationLevel.NONE;
            } else if ("AGGRESSIVE".equals(request.getSimplificationMode())) {
                return SimplificationLevel.EXTREME;
            }
        }

        // 줌인 상태에서도 긴 시간 범위는 추가 간소화
        if (hours > 24 && zoom >= 12) {
            return SimplificationLevel.MODERATE;
        }

        // 근거리 뷰 (줌 12+): 경량 간소화
        return SimplificationLevel.LIGHT;
    }

    // 도우미 메서드들
    private List<double[]> mergeGeometry(List<double[]> existing, List<double[]> newData) {
        if (existing == null) existing = new ArrayList<>();
        existing.addAll(newData);
        return existing;
    }

    private List<String> mergeTimestamps(List<String> existing, List<String> newData) {
        if (existing == null) existing = new ArrayList<>();
        existing.addAll(newData);
        return existing;
    }

    private List<Double> mergeSpeeds(List<Double> existing, List<Double> newData) {
        if (existing == null) existing = new ArrayList<>();
        existing.addAll(newData);
        return existing;
    }

    // 내부 클래스들
    private static class TimeChunk {
        LocalDateTime start;
        LocalDateTime end;

        TimeChunk(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }
    }

    // 테이블 전략
    private enum TableStrategy {
        FIVE_MINUTE("signal.t_vessel_tracks_5min"),
        HOURLY("signal.t_vessel_tracks_hourly"),
        DAILY("signal.t_vessel_tracks_daily");

        private final String tableName;

        TableStrategy(String tableName) {
            this.tableName = tableName;
        }

        public String getTableName() {
            return tableName;
        }
    }

    // 시간 범위
    private static class TimeRange {
        private final LocalDateTime start;
        private final LocalDateTime end;

        TimeRange(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        @Override
        public String toString() {
            return "[" + start + " ~ " + end + "]";
        }
    }

    // ChunkStats 생성 헬퍼 메서드
    private ChunkStats createChunkStats(List<CompactVesselTrack> compactTracks,
                                        Set<String> uniqueVesselIds, int processedMinutes) {
        ChunkStats stats = new ChunkStats();
        stats.setTrackCount(compactTracks.size());
        stats.setTotalTracks(compactTracks.stream()
                .mapToInt(CompactVesselTrack::getPointCount)
                .sum());

        // 시간 기반 진행률만 사용
        double timeProgress = estimatedTotalMinutes > 0 ?
                (double) processedMinutes / estimatedTotalMinutes * 100 : 0;
        stats.setProgressPercentage(Math.min(100.0, timeProgress));
        stats.setTimeProgress(timeProgress);

        // 기본 정보
        stats.setProcessedVessels(uniqueVesselIds.size());
        stats.setProcessedMinutes(processedMinutes);
        stats.setTotalMinutes(estimatedTotalMinutes);

        // 경과 시간
        stats.setElapsedMillis(System.currentTimeMillis() - queryStartTime);

        return stats;
    }

    // 현재 청크 인덱스 추적용
    private int currentGlobalChunkIndex = 0;
    private int vesselLogCount = 0; // 로그 카운트 추가

    private int getCurrentChunkIndex() {
        return currentGlobalChunkIndex;
    }

    /**
     * 범위를 날짜별로 그룹화
     */
    private Map<LocalDate, List<TimeRange>> groupRangesByDate(List<TimeRange> ranges) {
        Map<LocalDate, List<TimeRange>> dateGroups = new TreeMap<>();

        for (TimeRange range : ranges) {
            LocalDate date = range.getStart().toLocalDate();
            dateGroups.computeIfAbsent(date, k -> new ArrayList<>()).add(range);
        }

        return dateGroups;
    }

    /**
     * 범위를 시간 윈도우로 그룹화 (예: 6시간 단위)
     */
    private Map<String, List<TimeRange>> groupRangesByTimeWindow(List<TimeRange> ranges, int windowHours) {
        Map<String, List<TimeRange>> timeGroups = new LinkedHashMap<>();

        for (TimeRange range : ranges) {
            LocalDateTime windowStart = range.getStart()
                    .withHour((range.getStart().getHour() / windowHours) * windowHours)
                    .withMinute(0).withSecond(0).withNano(0);

            String key = windowStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH"));
            timeGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(range);
        }

        return timeGroups;
    }

    /**
     * 기준 시간을 사용하여 테이블 범위 처리 (M값 재계산)
     */
    private List<CompactVesselTrack> processTableRangeWithBaseTime(
            TrackQueryRequest request, TableStrategy strategy, TimeRange range, LocalDateTime dayBaseTime) {

        // 기본 처리 후 M값 보정
        List<CompactVesselTrack> tracks = processTableRange(request, strategy, range);

        // 각 트랙의 timestamp를 dayBaseTime 기준으로 재계산 및 간소화
        for (CompactVesselTrack track : tracks) {
            List<String> adjustedTimestamps = new ArrayList<>();
            List<double[]> simplifiedGeometry = new ArrayList<>();
            List<Double> simplifiedSpeeds = new ArrayList<>();
            List<String> simplifiedTimestamps = new ArrayList<>();

            // 시간 조정 (Unix timestamp 지원)
            for (String timestamp : track.getTimestamps()) {
                LocalDateTime time;
                
                // Unix timestamp 감지 (10자리 이상 숫자)
                if (timestamp.matches("\\d{10,}")) {
                    // Unix timestamp는 이미 UTC이므로 바로 변환
                    time = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(Long.parseLong(timestamp)), 
                        java.time.ZoneId.systemDefault());
                    // Unix timestamp는 조정 없이 그대로 사용
                    adjustedTimestamps.add(timestamp);
                } else {
                    time = LocalDateTime.parse(timestamp, TIMESTAMP_FORMATTER);
                    long secondsFromBase = ChronoUnit.SECONDS.between(dayBaseTime, time);
                    LocalDateTime adjustedTime = dayBaseTime.plusSeconds(secondsFromBase);
                    adjustedTimestamps.add(TIMESTAMP_FORMATTER.format(adjustedTime));
                }
            }

            // 간소화 적용 (시간 기반 샘플링 포함)
            if (track.getGeometry().size() > 1) {
                int originalSize = track.getGeometry().size();
                double[] prevPoint = null;
                LocalDateTime prevTime = null;

                for (int i = 0; i < track.getGeometry().size(); i++) {
                    double[] point = track.getGeometry().get(i);
                    LocalDateTime currentTime;
                    String tsStr = adjustedTimestamps.get(i);
                    
                    // Unix timestamp 감지
                    if (tsStr.matches("\\d{10,}")) {
                        currentTime = LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(Long.parseLong(tsStr)), 
                            java.time.ZoneId.systemDefault());
                    } else {
                        currentTime = LocalDateTime.parse(tsStr, TIMESTAMP_FORMATTER);
                    }
                    boolean include = false;

                    // 첫 포인트나 마지막 포인트는 항상 포함
                    if (i == 0 || i == track.getGeometry().size() - 1) {
                        include = true;
                    } else if (prevPoint != null && prevTime != null) {
                        double distance = calculateDistance(prevPoint[1], prevPoint[0], point[1], point[0]);
                        long minutesSincePrev = ChronoUnit.MINUTES.between(prevTime, currentTime);

                        // 거리 또는 시간 기반 포함 여부 결정 (균형잡힌 간소화)
                        if (strategy == TableStrategy.DAILY) {
                            // Daily: 이미 집계된 데이터 - 추가 간소화 최소화
                            include = distance > 0.05 || minutesSincePrev >= 60;
                        } else if (strategy == TableStrategy.HOURLY) {
                            // Hourly: 2km(1.08nm) 이상 이동 또는 60분 이상 경과
                            include = distance > 1.08 || minutesSincePrev >= 60;
                        } else {
                            // 5min: 1km(0.54nm) 이상 이동 또는 30분 이상 경과
                            include = distance > 0.54 || minutesSincePrev >= 30;
                        }

                        // 저속 선박은 더 강하게 간소화 (속도 정보가 있을 때만)
                        if (track.getAvgSpeed() > 0 && track.getAvgSpeed() < 5.0) {
                            // 5 knots 미만 저속 선박: 2.8km(1.5nm) 이상 이동 또는 45분 이상 경과
                            include = distance > 1.5 || minutesSincePrev >= 45;
                        }
                    }

                    if (include) {
                        simplifiedGeometry.add(point);
                        simplifiedTimestamps.add(adjustedTimestamps.get(i));
                        simplifiedSpeeds.add(track.getSpeeds().get(i));
                        prevPoint = point;
                        prevTime = currentTime;
                    }
                }

                // 줌 레벨에 따른 추가 샘플링
                if (request.getZoomLevel() != null && request.getZoomLevel() < 10) {
                    int sampleRate = request.getZoomLevel() < 6 ? 10 :
                            request.getZoomLevel() < 8 ? 5 : 2;

                    // 매 N번째 포인트만 유지 (첨 포인트와 마지막 포인트는 항상 포함)
                    List<double[]> sampledGeometry = new ArrayList<>();
                    List<String> sampledTimestamps = new ArrayList<>();
                    List<Double> sampledSpeeds = new ArrayList<>();

                    for (int i = 0; i < simplifiedGeometry.size(); i++) {
                        if (i % sampleRate == 0 || i == simplifiedGeometry.size() - 1) {
                            sampledGeometry.add(simplifiedGeometry.get(i));
                            sampledTimestamps.add(simplifiedTimestamps.get(i));
                            sampledSpeeds.add(simplifiedSpeeds.get(i));
                        }
                    }

                    track.setGeometry(sampledGeometry);
                    track.setTimestamps(sampledTimestamps);
                    track.setSpeeds(sampledSpeeds);
                    track.setPointCount(sampledGeometry.size());

                    // 간소화 결과 로그 (처음 10개 선박만)
                    if (vesselLogCount++ < 10) {
                        double reductionRate = (1 - (double)sampledGeometry.size() / originalSize) * 100;
                        log.info("[{}] Vessel {} simplified: {} -> {} points ({}% reduced, zoom: {}, speed: {} knots)",
                                strategy, track.getVesselId(), originalSize, sampledGeometry.size(),
                                Math.round(reductionRate), request.getZoomLevel(), track.getAvgSpeed());
                    }
                } else {
                    track.setGeometry(simplifiedGeometry);
                    track.setTimestamps(simplifiedTimestamps);
                    track.setSpeeds(simplifiedSpeeds);
                    track.setPointCount(simplifiedGeometry.size());

                    // 간소화 결과 로그 (처음 10개 선박만)
                    if (vesselLogCount++ < 10) {
                        double reductionRate = (1 - (double)simplifiedGeometry.size() / originalSize) * 100;
                        log.info("[{}] Vessel {} simplified: {} -> {} points ({}% reduced, speed: {} knots)",
                                strategy, track.getVesselId(), originalSize, simplifiedGeometry.size(),
                                Math.round(reductionRate), track.getAvgSpeed());
                    }
                }
            } else {
                track.setTimestamps(adjustedTimestamps);
            }
        }

        return tracks;
    }

    /**
     * Daily 전략 처리 (기존 방식 유지)
     */
    private void processDailyStrategy(List<TimeRange> ranges, TrackQueryRequest request, String queryId,
                                      Consumer<TrackChunkResponse> chunkConsumer,
                                      Consumer<QueryStatusUpdate> statusConsumer,
                                      int startChunkIndex,
                                      Set<String> uniqueVesselIds) throws Exception {

        currentGlobalChunkIndex = startChunkIndex;

        for (TimeRange range : ranges) {
            try {
                List<CompactVesselTrack> compactTracks = processTableRange(request, TableStrategy.DAILY, range);

                if (!compactTracks.isEmpty()) {
                    // 메시지 크기로 분할
                    List<List<CompactVesselTrack>> batches = splitByMessageSize(compactTracks);
                    for (List<CompactVesselTrack> batch : batches) {
                        TrackChunkResponse response = new TrackChunkResponse();
                        response.setQueryId(queryId);
                        response.setChunkIndex(currentGlobalChunkIndex++);
                        response.setIsLastChunk(false);
                        response.setTotalChunks(-1); // 마짉 청크에서 설정
                        response.setCompactTracks(batch);
                        // 처리된 시간 계산
                        String dailyKey = "daily_" + range.getStart();
                        processedTimeRanges.put(dailyKey, (int)Duration.between(range.getStart(), range.getEnd()).toMinutes());
                        int processedMin = processedTimeRanges.values().stream().mapToInt(Integer::intValue).sum();
                        response.setStats(createChunkStats(batch, uniqueVesselIds, processedMin));

                        chunkConsumer.accept(response);

                        batch.forEach(track -> uniqueVesselIds.add(track.getVesselId()));

                        double timeProgress = (double) processedMin / estimatedTotalMinutes * 100;
                        statusConsumer.accept(new QueryStatusUpdate(
                                queryId,
                                "PROCESSING",
                                "Processing daily chunk " + currentGlobalChunkIndex,
                                Math.min(95.0, timeProgress)
                        ));

                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing daily range {}: {}", range, e.getMessage());
            }
        }
    }


}
