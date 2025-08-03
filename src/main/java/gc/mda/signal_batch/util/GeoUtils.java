//package gc.mda.signal_batch.util;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Component;
//
//import java.util.*;
//
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class GeoUtils {
//
//    @Autowired
//    @Qualifier("queryJdbcTemplate")
//    private JdbcTemplate queryJdbcTemplate;
//
//    // 대해구 캐시 (성능 최적화)
//    private Map<Integer, HaeguInfo> haeguCache = new HashMap<>();
//    private boolean cacheInitialized = false;
//
//    // 대해구 모드 활성화 플래그
//    private boolean useHaeguMode = true;
//
//    /**
//     * 좌표에 해당하는 타일 ID 반환
//     */
//    public String getTileId(double lat, double lon, int level) {
//        if (useHaeguMode) {
//            HaeguTileInfo info = getHaeguTileInfo(lat, lon, level);
//            return info != null ? info.tileId : null;
//        }
//        // Legacy mode fallback
//        return getLegacyTileId(lat, lon, level);
//    }
//
//    /**
//     * 좌표에 해당하는 모든 레벨의 타일 ID 반환
//     */
//    public List<String> getTileIdsForPoint(double lat, double lon, Integer maxLevel) {
//        if (useHaeguMode) {
//            return getHaeguTileIds(lat, lon, maxLevel);
//        }
//        return getLegacyTileIds(lat, lon, maxLevel);
//    }
//
//    /**
//     * 타일의 면적 계산 (km²)
//     */
//    public double getTileArea(String tileId) {
//        if (useHaeguMode && tileId.startsWith("H")) {
//            return getHaeguTileArea(tileId);
//        }
//        return getLegacyTileArea(tileId);
//    }
//
//    /**
//     * 두 좌표 간 거리 계산 (Haversine formula, km)
//     */
//    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
//        double R = 6371; // 지구 반경 (km)
//        double dLat = Math.toRadians(lat2 - lat1);
//        double dLon = Math.toRadians(lon2 - lon1);
//
//        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
//                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
//                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
//
//        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
//        return R * c;
//    }
//
//    /**
//     * 타일 경계 좌표 반환
//     */
//    public TileBounds getTileBounds(String tileId) {
//        if (useHaeguMode && tileId.startsWith("H")) {
//            return getHaeguTileBounds(tileId);
//        }
//        return getLegacyTileBounds(tileId);
//    }
//
//    // ==================== 대해구 관련 메서드 ====================
//
//    private HaeguTileInfo getHaeguTileInfo(double lat, double lon, Integer level) {
//        if (!cacheInitialized) {
//            initializeCache();
//        }
//
//        Integer haeguNo = findHaeguNo(lat, lon);
//        if (haeguNo == null) {
//            return null;
//        }
//
//        HaeguInfo haeguInfo = haeguCache.get(haeguNo);
//        if (haeguInfo == null) {
//            return null;
//        }
//
//        if (level == null || level == 0) {
//            return new HaeguTileInfo("H" + haeguNo, 0, haeguNo, null);
//        }
//
//        Integer sohaeguNo = calculateSohaeguNo(lat, lon, haeguInfo);
//        String tileId = String.format("H%d_S%d", haeguNo, sohaeguNo);
//
//        return new HaeguTileInfo(tileId, 1, haeguNo, sohaeguNo);
//    }
//
//    private List<String> getHaeguTileIds(double lat, double lon, Integer maxLevel) {
//        List<String> tileIds = new ArrayList<>();
//
//        HaeguTileInfo level0 = getHaeguTileInfo(lat, lon, 0);
//        if (level0 != null) {
//            tileIds.add(level0.tileId);
//
//            if (maxLevel == null || maxLevel >= 1) {
//                HaeguTileInfo level1 = getHaeguTileInfo(lat, lon, 1);
//                if (level1 != null) {
//                    tileIds.add(level1.tileId);
//                }
//            }
//        }
//
//        return tileIds;
//    }
//
//    private double getHaeguTileArea(String tileId) {
//        boolean isSohaegu = tileId.contains("_S");
//
//        double latRad = Math.toRadians(36.0);
//        double lonDistance = 111.32 * Math.cos(latRad);
//        double latDistance = 110.54;
//
//        if (isSohaegu) {
//            double size = 0.5 / 3.0;
//            return (size * lonDistance) * (size * latDistance);
//        } else {
//            return (0.5 * lonDistance) * (0.5 * latDistance);
//        }
//    }
//
//    private TileBounds getHaeguTileBounds(String tileId) {
//        try {
//            Map<String, Object> result = queryJdbcTemplate.queryForMap(
//                "SELECT min_lat, min_lon, max_lat, max_lon " +
//                "FROM signal.t_grid_tiles WHERE tile_id = ?",
//                tileId
//            );
//
//            return new TileBounds(
//                ((Number) result.get("min_lon")).doubleValue(),
//                ((Number) result.get("max_lon")).doubleValue(),
//                ((Number) result.get("min_lat")).doubleValue(),
//                ((Number) result.get("max_lat")).doubleValue()
//            );
//        } catch (Exception e) {
//            log.error("Failed to get bounds for tile: {}", tileId, e);
//            return null;
//        }
//    }
//
//    private Integer findHaeguNo(double lat, double lon) {
//        for (Map.Entry<Integer, HaeguInfo> entry : haeguCache.entrySet()) {
//            HaeguInfo info = entry.getValue();
//            if (lat >= info.minLat && lat < info.maxLat &&
//                lon >= info.minLon && lon < info.maxLon) {
//                return entry.getKey();
//            }
//        }
//
//        try {
//            return queryJdbcTemplate.queryForObject(
//                "SELECT haegu_no FROM signal.t_haegu_definitions " +
//                "WHERE ? >= min_lat AND ? < max_lat AND ? >= min_lon AND ? < max_lon",
//                Integer.class, lat, lat, lon, lon
//            );
//        } catch (Exception e) {
//            log.debug("No haegu found for coordinates: {}, {}", lat, lon);
//            return null;
//        }
//    }
//
//    private Integer calculateSohaeguNo(double lat, double lon, HaeguInfo haeguInfo) {
//        double latSize = (haeguInfo.maxLat - haeguInfo.minLat) / 3.0;
//        double lonSize = (haeguInfo.maxLon - haeguInfo.minLon) / 3.0;
//
//        int row = (int) Math.floor((haeguInfo.maxLat - lat) / latSize);
//        row = Math.min(Math.max(row, 0), 2);
//
//        int col = (int) Math.floor((lon - haeguInfo.minLon) / lonSize);
//        col = Math.min(Math.max(col, 0), 2);
//
//        return row * 3 + col + 1;
//    }
//
//    private synchronized void initializeCache() {
//        if (cacheInitialized) {
//            return;
//        }
//
//        try {
//            List<Map<String, Object>> results = queryJdbcTemplate.queryForList(
//                "SELECT haegu_no, min_lat, min_lon, max_lat, max_lon " +
//                "FROM signal.t_haegu_definitions"
//            );
//
//            for (Map<String, Object> row : results) {
//                Integer haeguNo = (Integer) row.get("haegu_no");
//                HaeguInfo info = new HaeguInfo(
//                    ((Number) row.get("min_lat")).doubleValue(),
//                    ((Number) row.get("min_lon")).doubleValue(),
//                    ((Number) row.get("max_lat")).doubleValue(),
//                    ((Number) row.get("max_lon")).doubleValue()
//                );
//                haeguCache.put(haeguNo, info);
//            }
//
//            cacheInitialized = true;
//            log.info("Haegu cache initialized with {} entries", haeguCache.size());
//        } catch (Exception e) {
//            log.error("Failed to initialize haegu cache", e);
//        }
//    }
//
//    // ==================== Legacy 메서드 ====================
//
//    private static final double[] TILE_SIZES = {0.09, 0.045, 0.009};
//    private static final double MIN_LON = 124.0;
//    private static final double MAX_LON = 132.0;
//    private static final double MIN_LAT = 33.0;
//    private static final double MAX_LAT = 39.0;
//
//    private String getLegacyTileId(double lat, double lon, int level) {
//        if (level < 0 || level >= TILE_SIZES.length) {
//            throw new IllegalArgumentException("Invalid tile level: " + level);
//        }
//
//        double tileSize = TILE_SIZES[level];
//        int tileX = (int) Math.floor((lon - MIN_LON) / tileSize);
//        int tileY = (int) Math.floor((lat - MIN_LAT) / tileSize);
//
//        return String.format("L%d_%d_%d", level, tileX, tileY);
//    }
//
//    private List<String> getLegacyTileIds(double lat, double lon, Integer maxLevel) {
//        List<String> tileIds = new ArrayList<>();
//        int endLevel = maxLevel != null ? Math.min(maxLevel, TILE_SIZES.length - 1) : TILE_SIZES.length - 1;
//
//        for (int level = 0; level <= endLevel; level++) {
//            tileIds.add(getLegacyTileId(lat, lon, level));
//        }
//
//        return tileIds;
//    }
//
//    private double getLegacyTileArea(String tileId) {
//        int level = Integer.parseInt(tileId.substring(1, 2));
//        double tileSize = TILE_SIZES[level];
//
//        double latRad = Math.toRadians(36.0);
//        double lonDistance = 111.32 * Math.cos(latRad);
//        double latDistance = 110.54;
//
//        return (tileSize * lonDistance) * (tileSize * latDistance);
//    }
//
//    private TileBounds getLegacyTileBounds(String tileId) {
//        String[] parts = tileId.split("_");
//        int level = Integer.parseInt(parts[0].substring(1));
//        int tileX = Integer.parseInt(parts[1]);
//        int tileY = Integer.parseInt(parts[2]);
//
//        double tileSize = TILE_SIZES[level];
//
//        return new TileBounds(
//                MIN_LON + tileX * tileSize,
//                MIN_LON + (tileX + 1) * tileSize,
//                MIN_LAT + tileY * tileSize,
//                MIN_LAT + (tileY + 1) * tileSize
//        );
//    }
//
//    // ==================== 내부 클래스 ====================
//
//    private static class HaeguInfo {
//        final double minLat, minLon, maxLat, maxLon;
//
//        HaeguInfo(double minLat, double minLon, double maxLat, double maxLon) {
//            this.minLat = minLat;
//            this.minLon = minLon;
//            this.maxLat = maxLat;
//            this.maxLon = maxLon;
//        }
//    }
//
//    public static class HaeguTileInfo {
//        public final String tileId;
//        public final int level;
//        public final int haeguNo;
//        public final Integer sohaeguNo;
//
//        public HaeguTileInfo(String tileId, int level, int haeguNo, Integer sohaeguNo) {
//            this.tileId = tileId;
//            this.level = level;
//            this.haeguNo = haeguNo;
//            this.sohaeguNo = sohaeguNo;
//        }
//    }
//
//    public static class TileBounds {
//        public final double minLon;
//        public final double maxLon;
//        public final double minLat;
//        public final double maxLat;
//
//        public TileBounds(double minLon, double maxLon, double minLat, double maxLat) {
//            this.minLon = minLon;
//            this.maxLon = maxLon;
//            this.minLat = minLat;
//            this.maxLat = maxLat;
//        }
//
//        public String toWkt() {
//            return String.format("POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
//                    minLon, minLat,
//                    maxLon, minLat,
//                    maxLon, maxLat,
//                    minLon, maxLat,
//                    minLon, minLat
//            );
//        }
//    }
//}