package gc.mda.signal_batch.batch.processor;

import gc.mda.signal_batch.domain.vessel.model.VesselTrack;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 비정상 궤적 검출기 - 최종 개선 버전
 * 기기 오작동이나 네트워크 문제로 인한 명백한 비정상 궤적만 검출
 * 데이터 gap과 단일 포인트 케이스 처리
 */
@Slf4j
@Component
public class AbnormalTrackDetector {
    
    // 물리적 한계값 (매우 관대하게 설정)
    @SuppressWarnings("unused")
    private static final double VESSEL_PHYSICAL_LIMIT_KNOTS = 100.0;   // 선박 물리적 한계
    @SuppressWarnings("unused")
    private static final double AIRCRAFT_PHYSICAL_LIMIT_KNOTS = 600.0;  // 항공기 물리적 한계
    
    // 명백한 비정상만 검출하기 위한 임계값
    private static final double VESSEL_ABNORMAL_SPEED_KNOTS = 500.0;    // 선박 비정상 속도 (매우 관대)
    private static final double AIRCRAFT_ABNORMAL_SPEED_KNOTS = 800.0;  // 항공기 비정상 속도
    
    // 시간별 거리 임계값 (제곱근 스케일링 적용)
    private static final double BASE_DISTANCE_5MIN_NM = 20.0;  // 5분간 기준 거리 (2배로 증가)
    private static final double MIN_MOVEMENT_NM = 0.01;       // 최소 이동거리 (정박 판단)
    private static final double STATIONARY_SPEED_KNOTS = 0.5; // 정박 속도 기준
    
    // 데이터 gap 허용치
    private static final long MAX_NORMAL_GAP_MINUTES = 120;    // 2시간까지는 정상 gap으로 간주
    private static final long MIN_GAP_FOR_RELAXED_CHECK = 30;  // 30분 이상 gap은 완화된 검사
    
    private static final double EARTH_RADIUS_NM = 3440.065;
    private static final String AIRCRAFT_SIG_SRC_CD = "000019";
    
    @Data
    @Builder
    public static class AbnormalSegment {
        private String type;
        private int startIndex;
        private int endIndex;
        private double actualValue;
        private double threshold;
        private String description;
        private Map<String, Object> details;
    }
    
    @Data
    @Builder
    public static class AbnormalDetectionResult {
        private VesselTrack originalTrack;
        private VesselTrack correctedTrack;
        private List<AbnormalSegment> abnormalSegments;
        private boolean hasAbnormalities;
        
        public boolean hasAbnormalities() {
            return abnormalSegments != null && !abnormalSegments.isEmpty();
        }
    }
    
    /**
     * 5분 데이터용 비정상 검출 - 명백한 비정상만 검출
     */
    public AbnormalDetectionResult detectAbnormalTrack(VesselTrack track, VesselTrack previousTrack) {
        List<AbnormalSegment> abnormalSegments = new ArrayList<>();
        
        // 정박 상태 체크
        if (isStationary(track)) {
            log.debug("정박/저속 선박으로 판단, 비정상 검출 스킵 - vessel: {}, distance: {}nm, avgSpeed: {}kts",
                    track.getVesselKey(), track.getDistanceNm(), track.getAvgSpeed());
            return buildNormalResult(track);
        }
        
        // 1. 집계 메트릭 기반 검출 (매우 관대한 기준)
        abnormalSegments.addAll(checkAggregatedMetricsLenient(track));
        
        // 2. 이전 궤적과의 연결점 검증 (있는 경우에만)
        if (previousTrack != null && !isStationary(previousTrack)) {
            abnormalSegments.addAll(checkBucketTransitionLenient(previousTrack, track));
        }
        
        // 비정상이 없으면 정상 처리
        if (abnormalSegments.isEmpty()) {
            return buildNormalResult(track);
        }
        
        // 명백한 비정상만 제외
        VesselTrack correctedTrack = shouldExcludeTrack(abnormalSegments) ? null : track;
        
        log.info("명백한 비정상 궤적 검출 - vessel: {}, type: {}, value: {}",
                track.getVesselKey(), 
                abnormalSegments.get(0).getType(),
                abnormalSegments.get(0).getActualValue());
        
        return AbnormalDetectionResult.builder()
                .originalTrack(track)
                .correctedTrack(correctedTrack)
                .abnormalSegments(abnormalSegments)
                .hasAbnormalities(true)
                .build();
    }
    
    /**
     * Hourly/Daily 집계용 - Bucket 간 연결점만 매우 관대하게 검사
     */
    public AbnormalDetectionResult detectBucketTransitionOnly(VesselTrack track, VesselTrack previousTrack) {
        // 5분 데이터는 이미 검증되었으므로 bucket 간 전환만 확인
        if (previousTrack == null) {
            return buildNormalResult(track);
        }
        
        List<AbnormalSegment> abnormalSegments = checkBucketTransitionVeryLenient(previousTrack, track);
        
        if (abnormalSegments.isEmpty()) {
            return buildNormalResult(track);
        }
        
        // Hourly/Daily에서는 선박/항공기 구분하여 제외
        boolean isAircraft = AIRCRAFT_SIG_SRC_CD.equals(track.getSigSrcCd());
        double speedLimit = isAircraft ? 300.0 : 100.0; // 항공기 300, 선박 100
        
        boolean shouldExclude = abnormalSegments.stream()
                .anyMatch(seg -> seg.getActualValue() > speedLimit);
        
        VesselTrack correctedTrack = shouldExclude ? null : track;
        
        if (shouldExclude) {
            log.info("Hourly/Daily 명백한 비정상 전환 - vessel: {}, speed: {} knots",
                    track.getVesselKey(), abnormalSegments.get(0).getActualValue());
        }
        
        return AbnormalDetectionResult.builder()
                .originalTrack(track)
                .correctedTrack(correctedTrack)
                .abnormalSegments(abnormalSegments)
                .hasAbnormalities(!abnormalSegments.isEmpty())
                .build();
    }
    
    /**
     * 정박/저속 상태 판단
     */
    private boolean isStationary(VesselTrack track) {
        if (track.getDistanceNm() == null || track.getAvgSpeed() == null) {
            return false;
        }
        
        // 거리가 매우 작고 평균속도가 낮으면 정박 상태
        return track.getDistanceNm().doubleValue() < MIN_MOVEMENT_NM ||
               track.getAvgSpeed().doubleValue() < STATIONARY_SPEED_KNOTS;
    }
    
    /**
     * 단일 포인트 트랙인지 확인
     */
    private boolean isSinglePointTrack(VesselTrack track) {
        if (track.getStartPosition() == null || track.getEndPosition() == null) {
            return false;
        }
        
        // 시작과 끝 위치가 동일하고 시간도 동일하면 단일 포인트
        return track.getStartPosition().getLat().equals(track.getEndPosition().getLat()) &&
               track.getStartPosition().getLon().equals(track.getEndPosition().getLon()) &&
               track.getStartPosition().getTime().equals(track.getEndPosition().getTime());
    }
    
    /**
     * 관대한 집계 메트릭 검사 (5분 데이터용)
     */
    private List<AbnormalSegment> checkAggregatedMetricsLenient(VesselTrack track) {
        List<AbnormalSegment> abnormalSegments = new ArrayList<>();
        
        boolean isAircraft = AIRCRAFT_SIG_SRC_CD.equals(track.getSigSrcCd());
        double speedLimit = isAircraft ? AIRCRAFT_ABNORMAL_SPEED_KNOTS : VESSEL_ABNORMAL_SPEED_KNOTS;
        
        // 평균속도가 명백히 비정상인 경우만 검출
        if (track.getAvgSpeed() != null && track.getAvgSpeed().doubleValue() > speedLimit) {
            abnormalSegments.add(AbnormalSegment.builder()
                    .type("extreme_speed")
                    .actualValue(track.getAvgSpeed().doubleValue())
                    .threshold(speedLimit)
                    .description(String.format("극단적 비정상 속도: %.1f knots", 
                            track.getAvgSpeed().doubleValue()))
                    .build());
        }
        
        // 5분간 극단적 이동거리 (예: 100nm 이상)
        if (track.getDistanceNm() != null && track.getDistanceNm().doubleValue() > 100.0) {
            abnormalSegments.add(AbnormalSegment.builder()
                    .type("extreme_distance")
                    .actualValue(track.getDistanceNm().doubleValue())
                    .threshold(100.0)
                    .description(String.format("5분간 극단적 이동: %.1f nm", 
                            track.getDistanceNm().doubleValue()))
                    .build());
        }
        
        return abnormalSegments;
    }
    
    /**
     * 관대한 Bucket 전환 검사 (5분 데이터용)
     */
    private List<AbnormalSegment> checkBucketTransitionLenient(VesselTrack previousTrack, VesselTrack currentTrack) {
        List<AbnormalSegment> abnormalSegments = new ArrayList<>();
        
        if (previousTrack.getEndPosition() == null || currentTrack.getStartPosition() == null) {
            return abnormalSegments;
        }
        
        // 둘 중 하나가 단일 포인트면 검사 스킵
        if (isSinglePointTrack(previousTrack) || isSinglePointTrack(currentTrack)) {
            log.debug("단일 포인트 트랙 감지, bucket 전환 검사 스킵");
            return abnormalSegments;
        }
        
        double distance = calculateDistance(
                previousTrack.getEndPosition().getLat(), 
                previousTrack.getEndPosition().getLon(),
                currentTrack.getStartPosition().getLat(),
                currentTrack.getStartPosition().getLon()
        );
        
        long durationMinutes = java.time.Duration.between(
                previousTrack.getEndPosition().getTime(),
                currentTrack.getStartPosition().getTime()
        ).toMinutes();
        
        if (durationMinutes <= 0) return abnormalSegments;
        
        // 데이터 gap이 큰 경우 더 관대한 기준 적용
        boolean hasLargeGap = durationMinutes > MIN_GAP_FOR_RELAXED_CHECK;
        boolean isNormalGap = durationMinutes <= MAX_NORMAL_GAP_MINUTES;
        
        if (hasLargeGap && isNormalGap) {
            log.debug("데이터 gap {} 분 감지, 완화된 검사 적용", durationMinutes);
            // Gap이 30분~2시간인 경우 매우 관대한 기준
            return abnormalSegments;
        }
        
        double impliedSpeed = (distance * 60.0) / durationMinutes;
        
        // 제곱근 스케일링으로 시간에 따른 거리 임계값 계산
        double timeScale = Math.sqrt(durationMinutes / 5.0);
        double distanceThreshold = BASE_DISTANCE_5MIN_NM * timeScale * 3.0; // 3배 여유
        
        boolean isAircraft = AIRCRAFT_SIG_SRC_CD.equals(currentTrack.getSigSrcCd());
        double speedLimit = isAircraft ? AIRCRAFT_ABNORMAL_SPEED_KNOTS : VESSEL_ABNORMAL_SPEED_KNOTS;
        
        // 매우 명백한 비정상만 검출
        if (impliedSpeed > speedLimit && distance > distanceThreshold) {
            Map<String, Object> details = new HashMap<>();
            details.put("distance", distance);
            details.put("duration", durationMinutes);
            details.put("impliedSpeed", impliedSpeed);
            details.put("distanceThreshold", distanceThreshold);
            details.put("hasGap", hasLargeGap);
            
            abnormalSegments.add(AbnormalSegment.builder()
                    .type("extreme_transition")
                    .actualValue(impliedSpeed)
                    .threshold(speedLimit)
                    .description(String.format("극단적 bucket 전환: %.1f knots", impliedSpeed))
                    .details(details)
                    .build());
        }
        
        return abnormalSegments;
    }
    
    /**
     * 매우 관대한 Bucket 전환 검사 (Hourly/Daily용) - 수정됨
     */
    private List<AbnormalSegment> checkBucketTransitionVeryLenient(VesselTrack previousTrack, VesselTrack currentTrack) {
        List<AbnormalSegment> abnormalSegments = new ArrayList<>();
        
        if (previousTrack.getEndPosition() == null || currentTrack.getStartPosition() == null) {
            return abnormalSegments;
        }
        
        // 둘 중 하나가 단일 포인트면 검사 스킵
        if (isSinglePointTrack(previousTrack) || isSinglePointTrack(currentTrack)) {
            return abnormalSegments;
        }
        
        // 거리 계산 (집계된 위치 기반)
        double distance = calculateDistance(
                previousTrack.getEndPosition().getLat(), 
                previousTrack.getEndPosition().getLon(),
                currentTrack.getStartPosition().getLat(),
                currentTrack.getStartPosition().getLon()
        );
        
        // 시간 계산 - 위치 정보의 시간 사용
        long durationMinutes = java.time.Duration.between(
                previousTrack.getEndPosition().getTime(),
                currentTrack.getStartPosition().getTime()
        ).toMinutes();
        
        if (durationMinutes <= 0) return abnormalSegments;
        
        // Hourly/Daily는 데이터 gap이 클 수 있으므로 매우 관대하게
        if (durationMinutes > MAX_NORMAL_GAP_MINUTES) {
            log.debug("Hourly/Daily 큰 gap {} 분, 정상 처리", durationMinutes);
            return abnormalSegments;
        }
        
        double impliedSpeed = (distance * 60.0) / durationMinutes;
        
        // Hourly/Daily는 선박/항공기 구분하여 처리
        boolean isAircraft = AIRCRAFT_SIG_SRC_CD.equals(currentTrack.getSigSrcCd());
        double speedLimit = isAircraft ? 300.0 : 100.0;
        
        if (impliedSpeed > speedLimit) {
            Map<String, Object> details = new HashMap<>();
            details.put("distance", distance);
            details.put("duration", durationMinutes);
            details.put("impliedSpeed", impliedSpeed);
            details.put("prevEndTime", previousTrack.getEndPosition().getTime());
            details.put("currStartTime", currentTrack.getStartPosition().getTime());
            
            abnormalSegments.add(AbnormalSegment.builder()
                    .type("impossible_transition")
                    .actualValue(impliedSpeed)
                    .threshold(speedLimit)
                    .description(String.format("물리적 불가능 전환: %.1f knots", impliedSpeed))
                    .details(details)
                    .build());
        }
        
        return abnormalSegments;
    }
    
    /**
     * 정상 결과 생성
     */
    private AbnormalDetectionResult buildNormalResult(VesselTrack track) {
        return AbnormalDetectionResult.builder()
                .originalTrack(track)
                .correctedTrack(track)
                .abnormalSegments(new ArrayList<>())
                .hasAbnormalities(false)
                .build();
    }
    
    /**
     * 트랙 제외 여부 판단
     */
    private boolean shouldExcludeTrack(List<AbnormalSegment> abnormalSegments) {
        // extreme_* 타입의 비정상만 제외
        return abnormalSegments.stream()
                .anyMatch(seg -> seg.getType().startsWith("extreme_") || 
                               seg.getType().equals("impossible_transition"));
    }
    
    /**
     * Haversine 거리 계산
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_NM * c;
    }
}