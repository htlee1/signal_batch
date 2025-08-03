package gc.mda.signal_batch.service.filter;

import gc.mda.signal_batch.dto.websocket.TrackQueryRequest;
import gc.mda.signal_batch.dto.websocket.ViewportFilter;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AdvancedTrackFilter {
    
    /**
     * SQL WHERE 절 생성
     */
    public FilterResult buildWhereClause(TrackQueryRequest request, String tableAlias) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        
        // 시간 필터
        conditions.add(String.format("%s.time_bucket >= ?", tableAlias));
        parameters.put("startTime", request.getStartTime());
        
        conditions.add(String.format("%s.time_bucket < ?", tableAlias));
        parameters.put("endTime", request.getEndTime());
        
        // Viewport 필터
        if (request.getViewport() != null && request.getViewport().isValid()) {
            String viewportCondition = buildViewportCondition(request.getViewport(), tableAlias);
            conditions.add(viewportCondition);
        }
        
        // 해구 필터
        if (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) {
            String haeguCondition = buildHaeguCondition(request.getHaeguNumbers(), tableAlias);
            conditions.add(haeguCondition);
        }
        
        // 영역 필터
        if (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) {
            String areaCondition = buildAreaCondition(request.getAreaIds(), tableAlias);
            conditions.add(areaCondition);
        }
        
        // 선박 필터
        if (request.getVesselIds() != null && !request.getVesselIds().isEmpty()) {
            String vesselCondition = buildVesselCondition(request.getVesselIds(), tableAlias);
            conditions.add(vesselCondition);
        }
        
        // WHERE 절 조합
        String whereClause = conditions.isEmpty() ? "" : 
            " WHERE " + String.join(" AND ", conditions);
        
        return FilterResult.builder()
            .whereClause(whereClause)
            .parameters(parameters)
            .hasFilters(!conditions.isEmpty())
            .build();
    }
    
    /**
     * 복합 필터 빌더
     */
    public CompositeFilter createCompositeFilter(TrackQueryRequest request) {
        CompositeFilter.CompositeFilterBuilder builder = CompositeFilter.builder();
        
        // 기본 필터
        builder.timeRange(new TimeRange(request.getStartTime(), request.getEndTime()));
        
        // 공간 필터
        if (request.getViewport() != null) {
            builder.spatialFilter(new SpatialFilter(
                request.getViewport(),
                request.getHaeguNumbers(),
                request.getAreaIds()
            ));
        }
        
        // 선박 필터
        if (request.getVesselIds() != null) {
            builder.vesselFilter(new VesselFilter(request.getVesselIds()));
        }
        
        return builder.build();
    }
    
    /**
     * 동적 필터 최적화
     * 필터 조건에 따라 최적의 쿼리 전략 선택
     */
    public QueryStrategy optimizeQueryStrategy(CompositeFilter filter) {
        // 선박 ID가 적으면 선박별 쿼리
        if (filter.hasVesselFilter() && filter.getVesselFilter().getVesselIds().size() <= 10) {
            return QueryStrategy.VESSEL_BASED;
        }
        
        // 특정 영역이면 영역 기반 쿼리
        if (filter.hasSpatialFilter() && filter.getSpatialFilter().hasAreaFilter()) {
            return QueryStrategy.AREA_BASED;
        }
        
        // 해구 필터가 있으면 해구 기반 쿼리
        if (filter.hasSpatialFilter() && filter.getSpatialFilter().hasHaeguFilter()) {
            return QueryStrategy.HAEGU_BASED;
        }
        
        // 기본: 시간 기반 쿼리
        return QueryStrategy.TIME_BASED;
    }
    
    // Private helper methods
    
    private String buildViewportCondition(ViewportFilter viewport, String alias) {
        return String.format(
            "ST_Intersects(%s.track_geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))",
            alias
        );
    }
    
    private String buildHaeguCondition(List<Integer> haeguNumbers, String alias) {
        String placeholders = haeguNumbers.stream()
            .map(n -> "?")
            .collect(Collectors.joining(","));
        
        // 대해구와 소해구 모두 확인
        return String.format(
            "EXISTS (SELECT 1 FROM t_grid_tiles gt WHERE " +
            "(gt.large_grid_no IN (%s) OR gt.grid_no IN (%s)) AND " +
            "ST_Intersects(%s.track_geom, gt.boundary))",
            placeholders, placeholders, alias
        );
    }
    
    private String buildAreaCondition(List<String> areaIds, String alias) {
        String placeholders = areaIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(","));
        
        return String.format(
            "EXISTS (SELECT 1 FROM t_areas a WHERE " +
            "a.area_id IN (%s) AND " +
            "ST_Intersects(%s.track_geom, a.boundary))",
            placeholders, alias
        );
    }
    
    private String buildVesselCondition(List<String> vesselIds, String alias) {
        String placeholders = vesselIds.stream()
            .map(id -> "?")
            .collect(Collectors.joining(","));
        
        return String.format("%s.target_id IN (%s)", alias, placeholders);
    }
    
    // Inner classes
    
    @Data
    @Builder
    public static class FilterResult {
        private String whereClause;
        private Map<String, Object> parameters;
        private boolean hasFilters;
    }
    
    @Data
    @Builder
    public static class CompositeFilter {
        private TimeRange timeRange;
        private SpatialFilter spatialFilter;
        private VesselFilter vesselFilter;
        
        public boolean hasSpatialFilter() {
            return spatialFilter != null;
        }
        
        public boolean hasVesselFilter() {
            return vesselFilter != null && !vesselFilter.getVesselIds().isEmpty();
        }
    }
    
    @Data
    public static class TimeRange {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        
        public long getDurationHours() {
            return java.time.Duration.between(startTime, endTime).toHours();
        }
    }
    
    @Data
    public static class SpatialFilter {
        private final ViewportFilter viewport;
        private final List<Integer> haeguNumbers;
        private final List<String> areaIds;
        
        public boolean hasHaeguFilter() {
            return haeguNumbers != null && !haeguNumbers.isEmpty();
        }
        
        public boolean hasAreaFilter() {
            return areaIds != null && !areaIds.isEmpty();
        }
    }
    
    @Data
    public static class VesselFilter {
        private final List<String> vesselIds;
        
        public boolean isMultiVessel() {
            return vesselIds.size() > 1;
        }
    }
    
    public enum QueryStrategy {
        TIME_BASED("시간 기반 쿼리"),
        VESSEL_BASED("선박 기반 쿼리"),
        AREA_BASED("영역 기반 쿼리"),
        HAEGU_BASED("해구 기반 쿼리");
        
        private final String description;
        
        QueryStrategy(String description) {
            this.description = description;
        }
    }
    
    /**
     * 캐시 키 생성기
     * 동일한 필터 조건에 대한 캐시 키 생성
     */
    public String generateCacheKey(CompositeFilter filter) {
        List<String> parts = new ArrayList<>();
        
        // 시간 범위 (5분 단위로 정규화)
        long startMinutes = filter.getTimeRange().getStartTime().getMinute() / 5 * 5;
        long endMinutes = filter.getTimeRange().getEndTime().getMinute() / 5 * 5;
        parts.add(String.format("T:%d-%d", startMinutes, endMinutes));
        
        // 공간 필터
        if (filter.hasSpatialFilter()) {
            SpatialFilter spatial = filter.getSpatialFilter();
            
            if (spatial.getViewport() != null) {
                parts.add(String.format("V:%.2f,%.2f,%.2f,%.2f",
                    spatial.getViewport().getMinLon(),
                    spatial.getViewport().getMinLat(),
                    spatial.getViewport().getMaxLon(),
                    spatial.getViewport().getMaxLat()));
            }
            
            if (spatial.hasHaeguFilter()) {
                String haeguKey = spatial.getHaeguNumbers().stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
                parts.add("H:" + haeguKey);
            }
            
            if (spatial.hasAreaFilter()) {
                String areaKey = spatial.getAreaIds().stream()
                    .sorted()
                    .collect(Collectors.joining(","));
                parts.add("A:" + areaKey);
            }
        }
        
        // 선박 필터
        if (filter.hasVesselFilter()) {
            String vesselKey = filter.getVesselFilter().getVesselIds().stream()
                .sorted()
                .collect(Collectors.joining(","));
            parts.add("S:" + vesselKey);
        }
        
        return String.join("|", parts);
    }
}