package gc.mda.signal_batch.global.util;

import gc.mda.signal_batch.global.websocket.dto.TrackQueryRequest;
import gc.mda.signal_batch.global.websocket.dto.ViewportFilter;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.LocalDateTime;


@UtilityClass
public class ValidationUtil {

    private static final double MIN_LONGITUDE = -180.0;
    private static final double MAX_LONGITUDE = 180.0;
    private static final double MIN_LATITUDE = -90.0;
    private static final double MAX_LATITUDE = 90.0;
    private static final int MAX_QUERY_DAYS = 90;
    private static final int MIN_CHUNK_SIZE = 100;
    private static final int MAX_CHUNK_SIZE = 20000;

    public static void validateTrackQueryRequest(TrackQueryRequest request) {
        // 시간 검증
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new IllegalArgumentException("Start time and end time are required");
        }

        if (request.getStartTime().isAfter(request.getEndTime())) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        Duration duration = Duration.between(request.getStartTime(), request.getEndTime());
        if (duration.toDays() > MAX_QUERY_DAYS) {
            throw new IllegalArgumentException(
                String.format("Query period cannot exceed %d days", MAX_QUERY_DAYS)
            );
        }

        // 미래 시간 체크
        if (request.getEndTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("End time cannot be in the future");
        }

        // 최소 하나의 필터 필요
        boolean hasFilter = request.getViewport() != null ||
                          (request.getHaeguNumbers() != null && !request.getHaeguNumbers().isEmpty()) ||
                          (request.getAreaIds() != null && !request.getAreaIds().isEmpty()) ||
                          (request.getVesselIds() != null && !request.getVesselIds().isEmpty());

        if (!hasFilter) {
            throw new IllegalArgumentException("At least one filter must be specified");
        }

        // Viewport 검증
        if (request.getViewport() != null) {
            validateViewport(request.getViewport());
        }

        // 청크 크기 검증
        if (request.getChunkSize() != null) {
            if (request.getChunkSize() < MIN_CHUNK_SIZE || request.getChunkSize() > MAX_CHUNK_SIZE) {
                throw new IllegalArgumentException(
                    String.format("Chunk size must be between %d and %d", MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
                );
            }
        }

        // 단순화 임계값 검증
        if (request.getSimplificationTolerance() != null && request.getSimplificationTolerance() < 0) {
            throw new IllegalArgumentException("Simplification tolerance must be non-negative");
        }
    }

    private static void validateViewport(ViewportFilter viewport) {
        // 경도 검증
        if (viewport.getMinLon() < MIN_LONGITUDE || viewport.getMinLon() > MAX_LONGITUDE) {
            throw new IllegalArgumentException(
                String.format("Min longitude must be between %f and %f", MIN_LONGITUDE, MAX_LONGITUDE)
            );
        }

        if (viewport.getMaxLon() < MIN_LONGITUDE || viewport.getMaxLon() > MAX_LONGITUDE) {
            throw new IllegalArgumentException(
                String.format("Max longitude must be between %f and %f", MIN_LONGITUDE, MAX_LONGITUDE)
            );
        }

        if (viewport.getMinLon() >= viewport.getMaxLon()) {
            throw new IllegalArgumentException("Min longitude must be less than max longitude");
        }

        // 위도 검증
        if (viewport.getMinLat() < MIN_LATITUDE || viewport.getMinLat() > MAX_LATITUDE) {
            throw new IllegalArgumentException(
                String.format("Min latitude must be between %f and %f", MIN_LATITUDE, MAX_LATITUDE)
            );
        }

        if (viewport.getMaxLat() < MIN_LATITUDE || viewport.getMaxLat() > MAX_LATITUDE) {
            throw new IllegalArgumentException(
                String.format("Max latitude must be between %f and %f", MIN_LATITUDE, MAX_LATITUDE)
            );
        }

        if (viewport.getMinLat() >= viewport.getMaxLat()) {
            throw new IllegalArgumentException("Min latitude must be less than max latitude");
        }

        // 영역 크기 검증 (너무 큰 영역 방지)
        double lonSpan = viewport.getMaxLon() - viewport.getMinLon();
        double latSpan = viewport.getMaxLat() - viewport.getMinLat();

        if (lonSpan > 20.0 || latSpan > 20.0) {
            throw new IllegalArgumentException("Viewport area is too large. Maximum span is 20 degrees");
        }
    }

    public static void validateQueryId(String queryId) {
        if (queryId == null || queryId.trim().isEmpty()) {
            throw new IllegalArgumentException("Query ID is required");
        }

        // UUID 형식 검증
        try {
            java.util.UUID.fromString(queryId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid query ID format");
        }
    }
}