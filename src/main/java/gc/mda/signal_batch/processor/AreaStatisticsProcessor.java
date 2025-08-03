package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.util.DataSourceLogger;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AreaStatisticsProcessor {

    @Qualifier("queryJdbcTemplate")
    private final JdbcTemplate queryJdbcTemplate;

    @Qualifier("queryDataSource")
    private final DataSource queryDataSource;

    // 메모리에 구역 정보 캐싱
    private final Map<String, AreaInfo> areaCache = new ConcurrentHashMap<>();
    private final List<AreaInfo> areaList = new ArrayList<>();

    // JTS 객체들
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final WKTReader wktReader = new WKTReader(geometryFactory);

    @PostConstruct
    public void init() {
        log.info("========== AreaStatisticsProcessor Initialization ==========");
        DataSourceLogger.logJdbcTemplateInfo("AreaStatisticsProcessor", queryJdbcTemplate);

        // t_areas 테이블 존재 확인
        boolean tableExists = DataSourceLogger.checkTableExists(
                "AreaStatisticsProcessor", queryJdbcTemplate, "signal", "t_areas"
        );

        if (!tableExists) {
            log.error("CRITICAL: Table signal.t_areas does not exist in query database!");
            log.error("Please run: scripts/sql/create-query-db-schema.sql on the query database");
        } else {
            // 초기화 시 구역 정보 로드
            loadAreas();
        }

        log.info("========== End of Initialization ==========");
    }

    @Data
    public static class AreaInfo {
        private String areaId;
        private String areaName;
        private String areaType;
        private String geomWkt;
        private Geometry geometry;  // JTS Geometry 객체
        private Envelope envelope;  // Bounding Box for quick filtering
    }

    @Data
    public static class AreaStatistics implements java.io.Serializable {
        private String areaId;
        private LocalDateTime timeBucket;
        private Integer vesselCount;
        private Integer inCount;
        private Integer outCount;
        private Map<String, VesselMovement> transitVessels;
        private Map<String, VesselMovement> stationaryVessels;
        private BigDecimal avgSog;
        private LocalDateTime createdAt;

        public AreaStatistics(String areaId, LocalDateTime timeBucket) {
            this.areaId = areaId;
            this.timeBucket = timeBucket;
            this.vesselCount = 0;
            this.inCount = 0;
            this.outCount = 0;
            this.transitVessels = new HashMap<>();
            this.stationaryVessels = new HashMap<>();
            this.avgSog = BigDecimal.ZERO;
        }
    }

    @Data
    public static class VesselMovement implements java.io.Serializable {
        private String vesselKey;
        private LocalDateTime enterTime;
        private LocalDateTime exitTime;
        private BigDecimal avgSpeed;
        private Integer pointCount;
    }

    @StepScope
    public ItemProcessor<List<VesselData>, List<AreaStatistics>> batchProcessor() {
        return batchProcessor(null);
    }

    @StepScope
    public ItemProcessor<List<VesselData>, List<AreaStatistics>> batchProcessor(
            @Value("#{jobParameters['timeBucketMinutes']}") Integer bucketMinutes) {

        return items -> {
            // 구역 정보가 없으면 빈 결과 반환
            if (areaList.isEmpty()) {
                log.warn("No areas loaded, skipping area statistics processing");
                return new ArrayList<>();
            }

            Map<String, AreaStatistics> statsMap = new HashMap<>();
            Map<String, Map<String, VesselMovement>> vesselTracker = new HashMap<>();

            for (VesselData item : items) {
                if (!item.isValidPosition()) {
                    continue;
                }

                // 메모리에서 속한 구역 찾기 (DB 쿼리 없음!)
                List<String> areaIds = findAreasForPointInMemory(item.getLat(), item.getLon());

                int bucketSize = bucketMinutes != null ? bucketMinutes : 5;  // 5분 단위로 변경
                LocalDateTime bucket = item.getMessageTime()
                        .truncatedTo(ChronoUnit.MINUTES)
                        .withMinute((item.getMessageTime().getMinute() / bucketSize) * bucketSize);

                for (String areaId : areaIds) {
                    String statsKey = areaId + "_" + bucket.toString();
                    AreaStatistics stats = statsMap.computeIfAbsent(statsKey,
                            k -> new AreaStatistics(areaId, bucket)
                    );

                    // 선박 이동 추적
                    String vesselKey = item.getVesselKey();
                    Map<String, VesselMovement> areaVessels = vesselTracker.computeIfAbsent(
                            areaId, k -> new HashMap<>()
                    );

                    VesselMovement movement = areaVessels.computeIfAbsent(vesselKey,
                            k -> {
                                VesselMovement vm = new VesselMovement();
                                vm.setVesselKey(vesselKey);
                                vm.setEnterTime(item.getMessageTime());
                                vm.setPointCount(0);
                                vm.setAvgSpeed(BigDecimal.ZERO);
                                stats.setInCount(stats.getInCount() + 1);
                                return vm;
                            }
                    );

                    movement.setExitTime(item.getMessageTime());
                    movement.setPointCount(movement.getPointCount() + 1);

                    // 평균 속도 계산
                    if (item.getSog() != null) {
                        BigDecimal currentTotal = movement.getAvgSpeed()
                                .multiply(BigDecimal.valueOf(movement.getPointCount() - 1));
                        movement.setAvgSpeed(
                                currentTotal.add(item.getSog())
                                        .divide(BigDecimal.valueOf(movement.getPointCount()), 2, BigDecimal.ROUND_HALF_UP)
                        );
                    }

                    // 정류/통과 구분 (10분 이상 체류 시 정류)
                    long stayMinutes = ChronoUnit.MINUTES.between(
                            movement.getEnterTime(), movement.getExitTime()
                    );

                    if (stayMinutes > 10) {
                        stats.getStationaryVessels().put(vesselKey, movement);
                    } else {
                        stats.getTransitVessels().put(vesselKey, movement);
                    }
                }
            }

            // 통계 최종 계산
            statsMap.values().forEach(stats -> {
                stats.setVesselCount(
                        stats.getTransitVessels().size() + stats.getStationaryVessels().size()
                );

                // 평균 속도 계산
                List<BigDecimal> allSpeeds = new ArrayList<>();
                stats.getTransitVessels().values().stream()
                        .map(VesselMovement::getAvgSpeed)
                        .filter(Objects::nonNull)
                        .forEach(allSpeeds::add);
                stats.getStationaryVessels().values().stream()
                        .map(VesselMovement::getAvgSpeed)
                        .filter(Objects::nonNull)
                        .forEach(allSpeeds::add);

                if (!allSpeeds.isEmpty()) {
                    BigDecimal totalSpeed = allSpeeds.stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    stats.setAvgSog(
                            totalSpeed.divide(BigDecimal.valueOf(allSpeeds.size()), 2, BigDecimal.ROUND_HALF_UP)
                    );
                }
            });

            return new ArrayList<>(statsMap.values());
        };
    }

    private void loadAreas() {
        log.info("Loading areas from query database...");
        DataSourceLogger.logJdbcTemplateInfo("AreaStatisticsProcessor.loadAreas", queryJdbcTemplate);

        String sql = "SELECT area_id, area_name, area_type, ST_AsText(area_geom) as geom_wkt FROM signal.t_areas";

        try {
            boolean exists = DataSourceLogger.checkTableExists(
                    "AreaStatisticsProcessor.loadAreas", queryJdbcTemplate, "signal", "t_areas"
            );

            if (exists) {
                List<AreaInfo> areas = queryJdbcTemplate.query(sql, (rs, rowNum) -> {
                    AreaInfo area = new AreaInfo();
                    area.setAreaId(rs.getString("area_id"));
                    area.setAreaName(rs.getString("area_name"));
                    area.setAreaType(rs.getString("area_type"));
                    area.setGeomWkt(rs.getString("geom_wkt"));

                    // WKT를 JTS Geometry로 변환
                    try {
                        Geometry geom = wktReader.read(area.getGeomWkt());
                        area.setGeometry(geom);
                        area.setEnvelope(geom.getEnvelopeInternal());
                    } catch (Exception e) {
                        log.error("Failed to parse WKT for area {}: {}", area.getAreaId(), e.getMessage());
                    }

                    return area;
                });

                areas.forEach(area -> {
                    areaCache.put(area.getAreaId(), area);
                    areaList.add(area);
                });

                log.info("Successfully loaded {} areas into memory cache", areas.size());
                log.info("Area types: {}", areas.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                AreaInfo::getAreaType,
                                java.util.stream.Collectors.counting()
                        )));
            } else {
                log.error("Cannot load areas - table signal.t_areas does not exist!");
            }
        } catch (Exception e) {
            log.error("Failed to load areas", e);
        }
    }

    /**
     * 메모리에서 포인트가 속한 구역 찾기 (DB 쿼리 없음!)
     */
    public List<String> findAreasForPointInMemory(double lat, double lon) {

        // JTS Point 생성
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));

        return areaList.parallelStream()
                .filter(area -> area.getGeometry() != null)
                .filter(area -> area.getEnvelope().contains(lon, lat))
                .filter(area -> {
                    try {
                        return area.getGeometry().contains(point);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .map(AreaInfo::getAreaId)
                .collect(Collectors.toList());
//        List<String> areaIds = new ArrayList<>();
//        // 모든 구역에 대해 contains 검사
//        for (AreaInfo area : areaList) {
//            if (area.getGeometry() == null) {
//                continue;
//            }
//
//            // 1. Envelope(Bounding Box)로 빠른 필터링
//            if (!area.getEnvelope().contains(lon, lat)) {
//                continue;
//            }
//
//            // 2. 정확한 contains 검사
//            try {
//                if (area.getGeometry().contains(point)) {
//                    areaIds.add(area.getAreaId());
//                }
//            } catch (Exception e) {
//                log.debug("Error checking contains for area {}: {}", area.getAreaId(), e.getMessage());
//            }
//        }
//
//        return areaIds;

    }

    /**
     * 캐시 상태 조회 (디버깅/모니터링용)
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("loadedAreas", areaList.size());
        stats.put("areaTypes", areaList.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AreaInfo::getAreaType,
                        java.util.stream.Collectors.counting()
                )));
        return stats;
    }
}