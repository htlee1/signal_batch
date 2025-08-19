package gc.mda.signal_batch.global.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Component
@RequiredArgsConstructor
public class HaeguGeoUtils {

    private final JdbcTemplate queryJdbcTemplate;

    // 대해구 캐시 (성능 최적화)
    private static Map<Integer, HaeguInfo> haeguCache = new HashMap<>();
    private static boolean cacheInitialized = false;

    // 추가: 좌표→타일ID 변환 결과 캐시 (Thread-safe)
    private static final ConcurrentHashMap<String, HaeguTileInfo> coordinateCache =
            new ConcurrentHashMap<>();

    // 캐시 최대 크기 (메모리 관리)
    private static final int MAX_CACHE_SIZE = 100000;

    @PostConstruct
    public void init() {
        log.info("Initializing HaeguGeoUtils...");
        initializeCache();
    }

    /**
     * 좌표에 해당하는 대해구/소해구 정보 반환 (캐시 적용)
     */
    public HaeguTileInfo getHaeguTileInfo(double lat, double lon, Integer level) {
        // 캐시 키 생성 (좌표를 0.0001도 단위로 반올림하여 캐시 효율성 향상)
        String cacheKey = String.format("%.4f,%.4f,%d", lat, lon, level != null ? level : 0);

        // 캐시에서 먼저 조회
        HaeguTileInfo cached = coordinateCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 캐시에 없으면 계산
        HaeguTileInfo result = calculateHaeguTileInfo(lat, lon, level);

        // 캐시 크기 관리 (간단한 LRU는 아니지만 메모리 오버플로 방지)
        if (coordinateCache.size() < MAX_CACHE_SIZE && result != null) {
            coordinateCache.put(cacheKey, result);
        }

        return result;
    }

    /**
     * 실제 대해구/소해구 계산 로직 (기존 getHaeguTileInfo 내용)
     */
    private HaeguTileInfo calculateHaeguTileInfo(double lat, double lon, Integer level) {
        // 캐시 초기화
        if (!cacheInitialized) {
            initializeCache();
        }

        // 대해구 찾기
        Integer haeguNo = findHaeguNo(lat, lon);
        if (haeguNo == null) {
            return null;
        }

        HaeguInfo haeguInfo = haeguCache.get(haeguNo);
        if (haeguInfo == null) {
            return null;
        }

        // Level 0: 대해구만 반환
        if (level == null || level == 0) {
            return new HaeguTileInfo("H" + haeguNo, 0, haeguNo, null);
        }

        // Level 1: 소해구도 계산
        Integer sohaeguNo = calculateSohaeguNo(lat, lon, haeguInfo);
        String tileId = String.format("H%d_S%d", haeguNo, sohaeguNo);

        return new HaeguTileInfo(tileId, 1, haeguNo, sohaeguNo);
    }

    /**
     * 좌표에 해당하는 모든 레벨의 타일 ID 반환 (캐시 활용)
     */
    public List<String> getTileIdsForPoint(double lat, double lon, Integer maxLevel) {
        List<String> tileIds = new ArrayList<>();

        HaeguTileInfo level0 = getHaeguTileInfo(lat, lon, 0);
        if (level0 != null) {
            tileIds.add(level0.tileId);

            if (maxLevel == null || maxLevel >= 1) {
                HaeguTileInfo level1 = getHaeguTileInfo(lat, lon, 1);
                if (level1 != null) {
                    tileIds.add(level1.tileId);
                }
            }
        }

        return tileIds;
    }

    /**
     * 타일 ID로 타일 정보 조회
     */
    public String getTileId(double lat, double lon, int level) {
        HaeguTileInfo info = getHaeguTileInfo(lat, lon, level);
        return info != null ? info.tileId : null;
    }

    /**
     * 타일의 면적 계산 (km²)
     */
    public double getTileArea(String tileId) {
        // 타일 ID 파싱
        if (tileId.startsWith("H")) {
            boolean isSohaegu = tileId.contains("_S");

            // 위도에 따른 거리 보정 (한반도 중심 위도 36도)
            double latRad = Math.toRadians(36.0);
            double lonDistance = 111.32 * Math.cos(latRad); // km per degree
            double latDistance = 110.54; // km per degree

            if (isSohaegu) {
                // 소해구: 0.5/3 도 크기
                double size = 0.5 / 3.0;
                return (size * lonDistance) * (size * latDistance);
            } else {
                // 대해구: 0.5도 크기
                return (0.5 * lonDistance) * (0.5 * latDistance);
            }
        }

        // 기존 타일 ID 처리 (호환성)
        return calculateLegacyTileArea(tileId);
    }

    /**
     * 대해구 번호 찾기
     */
    private Integer findHaeguNo(double lat, double lon) {
        // 빠른 검색을 위해 캐시 사용
        for (Map.Entry<Integer, HaeguInfo> entry : haeguCache.entrySet()) {
            HaeguInfo info = entry.getValue();
            if (lat >= info.minLat && lat < info.maxLat &&
                    lon >= info.minLon && lon < info.maxLon) {
                return entry.getKey();
            }
        }

        // 캐시에 없으면 DB 조회
        try {
            return queryJdbcTemplate.queryForObject(
                    "SELECT haegu_no FROM signal.t_haegu_definitions " +
                            "WHERE ? >= min_lat AND ? < max_lat AND ? >= min_lon AND ? < max_lon",
                    Integer.class, lat, lat, lon, lon
            );
        } catch (Exception e) {
            log.debug("No haegu found for coordinates: {}, {}", lat, lon);
            return null;
        }
    }

    /**
     * 소해구 번호 계산 (1-9)
     * 왼쪽 위부터: 1 2 3
     *              4 5 6
     *              7 8 9
     */
    private Integer calculateSohaeguNo(double lat, double lon, HaeguInfo haeguInfo) {
        double latSize = (haeguInfo.maxLat - haeguInfo.minLat) / 3.0;
        double lonSize = (haeguInfo.maxLon - haeguInfo.minLon) / 3.0;

        // 위에서부터 행 계산 (0, 1, 2)
        int row = (int) Math.floor((haeguInfo.maxLat - lat) / latSize);
        row = Math.min(Math.max(row, 0), 2); // 경계 처리

        // 왼쪽부터 열 계산 (0, 1, 2)
        int col = (int) Math.floor((lon - haeguInfo.minLon) / lonSize);
        col = Math.min(Math.max(col, 0), 2); // 경계 처리

        return row * 3 + col + 1;
    }

    /**
     * 캐시 초기화
     */
    private synchronized void initializeCache() {
        if (cacheInitialized) {
            return;
        }

        try {
            List<Map<String, Object>> results = queryJdbcTemplate.queryForList(
                    "SELECT haegu_no, min_lat, min_lon, max_lat, max_lon " +
                            "FROM signal.t_haegu_definitions"
            );

            for (Map<String, Object> row : results) {
                Integer haeguNo = (Integer) row.get("haegu_no");
                HaeguInfo info = new HaeguInfo(
                        ((Number) row.get("min_lat")).doubleValue(),
                        ((Number) row.get("min_lon")).doubleValue(),
                        ((Number) row.get("max_lat")).doubleValue(),
                        ((Number) row.get("max_lon")).doubleValue()
                );
                haeguCache.put(haeguNo, info);
            }

            cacheInitialized = true;
            log.info("Haegu cache initialized with {} entries", haeguCache.size());
        } catch (Exception e) {
            log.error("Failed to initialize haegu cache", e);
        }
    }

    /**
     * 기존 타일 면적 계산 (호환성용)
     */
    private double calculateLegacyTileArea(String tileId) {
        double[] TILE_SIZES = {0.09, 0.045, 0.009};
        try {
            int level = Integer.parseInt(tileId.substring(0, 1));
            if (level >= 0 && level < TILE_SIZES.length) {
                return TILE_SIZES[level];
            }
        } catch (Exception e) {
            log.debug("Failed to parse legacy tile ID: {}", tileId);
        }
        return 0.09; // 기본값
    }

    /**
     * 캐시 상태 조회 (모니터링용)
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("haeguCacheSize", haeguCache.size());
        stats.put("coordinateCacheSize", coordinateCache.size());
        stats.put("maxCacheSize", MAX_CACHE_SIZE);
        return stats;
    }

    /**
     * 캐시 클리어 (필요시 사용)
     */
    public void clearCoordinateCache() {
        coordinateCache.clear();
        log.info("Coordinate cache cleared");
    }

    // Inner classes
    private static class HaeguInfo {
        final double minLat;
        final double minLon;
        final double maxLat;
        final double maxLon;

        HaeguInfo(double minLat, double minLon, double maxLat, double maxLon) {
            this.minLat = minLat;
            this.minLon = minLon;
            this.maxLat = maxLat;
            this.maxLon = maxLon;
        }
    }

    public static class HaeguTileInfo {
        public final String tileId;
        public final int level;
        public final Integer haeguNo;
        public final Integer sohaeguNo;

        HaeguTileInfo(String tileId, int level, Integer haeguNo, Integer sohaeguNo) {
            this.tileId = tileId;
            this.level = level;
            this.haeguNo = haeguNo;
            this.sohaeguNo = sohaeguNo;
        }
    }
}