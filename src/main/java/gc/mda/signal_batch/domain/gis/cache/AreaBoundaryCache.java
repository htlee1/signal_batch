package gc.mda.signal_batch.domain.gis.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Slf4j
@Component
@RequiredArgsConstructor
public class AreaBoundaryCache {
    
    private final DataSource queryDataSource;
    private final Map<String, Polygon> areaPolygons = new ConcurrentHashMap<>();
    private final Map<Integer, Polygon> haeguPolygons = new ConcurrentHashMap<>();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final WKTReader wktReader = new WKTReader(geometryFactory);
    
    @PostConstruct
    public void loadBoundaries() {
        loadAreaBoundaries();
        loadHaeguBoundaries();
    }
    
    private void loadAreaBoundaries() {
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            String sql = """
                SELECT area_id, ST_AsText(area_geom) as wkt
                FROM signal.t_areas
            """;
            
            List<Map<String, Object>> areas = jdbcTemplate.queryForList(sql);
            
            for (Map<String, Object> area : areas) {
                String areaId = (String) area.get("area_id");
                String wkt = (String) area.get("wkt");
                try {
                    Polygon polygon = (Polygon) wktReader.read(wkt);
                    areaPolygons.put(areaId, polygon);
                } catch (Exception e) {
                    log.warn("Failed to parse geometry for area {}: {}", areaId, e.getMessage());
                }
            }
            
            log.info("Loaded {} areas into cache", areaPolygons.size());
        } catch (Exception e) {
            log.error("Failed to load areas cache", e);
        }
    }
    
    private void loadHaeguBoundaries() {
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(queryDataSource);
            
            String sql = """
                SELECT haegu_no, ST_AsText(geom) as wkt
                FROM signal.t_haegu_definitions
            """;
            
            List<Map<String, Object>> haegus = jdbcTemplate.queryForList(sql);
            
            for (Map<String, Object> haegu : haegus) {
                Integer haeguNo = (Integer) haegu.get("haegu_no");
                String wkt = (String) haegu.get("wkt");
                try {
                    Polygon polygon = (Polygon) wktReader.read(wkt);
                    haeguPolygons.put(haeguNo, polygon);
                } catch (Exception e) {
                    log.warn("Failed to parse geometry for haegu {}: {}", haeguNo, e.getMessage());
                }
            }
            
            log.info("Loaded {} haegus into cache", haeguPolygons.size());
        } catch (Exception e) {
            log.error("Failed to load haegus cache", e);
        }
    }
    
    // 포인트가 속한 모든 area ID 반환
    public Set<String> findAreasForPoint(double lat, double lon) {
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        
        return areaPolygons.entrySet().stream()
                .filter(entry -> entry.getValue().contains(point))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
    
    // 포인트가 속한 모든 haegu 번호 반환
    public Set<Integer> findHaegusForPoint(double lat, double lon) {
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        
        return haeguPolygons.entrySet().stream()
                .filter(entry -> entry.getValue().contains(point))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
    
    // 특정 area에 포인트가 포함되는지 확인
    public boolean isPointInArea(double lat, double lon, String areaId) {
        Polygon polygon = areaPolygons.get(areaId);
        if (polygon == null) return false;
        
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        return polygon.contains(point);
    }
    
    // 특정 haegu에 포인트가 포함되는지 확인
    public boolean isPointInHaegu(double lat, double lon, Integer haeguNo) {
        Polygon polygon = haeguPolygons.get(haeguNo);
        if (polygon == null) return false;
        
        Point point = geometryFactory.createPoint(new Coordinate(lon, lat));
        return polygon.contains(point);
    }
    
    // Job 실행 시 캐시 갱신
    public void refresh() {
        areaPolygons.clear();
        haeguPolygons.clear();
        loadBoundaries();
    }
    
    // 포인트가 속한 첫 번째 area ID 반환 (우선순위 기반)
    public String findAreaId(double lat, double lon) {
        Set<String> areas = findAreasForPoint(lat, lon);
        return areas.isEmpty() ? null : areas.iterator().next();
    }
    
    // 캐시 크기 반환
    public int getCacheSize() {
        return areaPolygons.size() + haeguPolygons.size();
    }
    
    // 캐시 클리어
    public void clearCache() {
        areaPolygons.clear();
        haeguPolygons.clear();
        log.info("Area boundary cache cleared");
    }
}