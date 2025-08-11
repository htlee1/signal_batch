package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.common.AreaBoundaryCache;
import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.util.HaeguGeoUtils;
import gc.mda.signal_batch.migration.unix_timestamp.MValueStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VesselTrackProcessor implements ItemProcessor<List<VesselData>, List<VesselTrack>> {

    private final HaeguGeoUtils haeguGeoUtils;
    private final AreaBoundaryCache areaBoundaryCache; // 캐시 활용
    
    @Autowired
    @Qualifier("relativeTimeStrategy")
    private MValueStrategy relativeStrategy;
    
    @Autowired
    @Qualifier("unixTimestampStrategy")
    private MValueStrategy unixStrategy;
    
    @Value("${vessel.batch.m-value.format:relative}")
    private String mValueFormat;
    
    @Value("${vessel.batch.m-value.dual-write:false}")
    private boolean dualWrite;

    @Override
    public List<VesselTrack> process(List<VesselData> items) throws Exception {
        if (items == null || items.isEmpty()) {
            return null;
        }

        // 이미 vessel key로 그룹화된 데이터이므로 직접 처리
        VesselTrack track = buildTrack(items);
        if (track != null && track.hasValidTrack()) {
            // VesselTrackStepConfig에서 비정상 궤적 필터링 및 저장 처리
            return List.of(track);
        }

        // 필터링 원인 로깅
        if (track == null) {
            log.debug("Track is null for vessel: {}", items.get(0).getVesselKey());
        } else if (!track.hasValidTrack()) {
            log.debug("Invalid track for vessel: {}, points: {}",
                    track.getVesselKey(), track.getTrackPoints() != null ? track.getTrackPoints().size() : 0);
        }

        return null;
    }

    private VesselTrack buildTrack(List<VesselData> vesselDataList) {
        VesselData first = vesselDataList.get(0);
        VesselData last = vesselDataList.get(vesselDataList.size() - 1);

        // 5분 버킷 계산
        LocalDateTime timeBucket = first.getMessageTime()
                .withSecond(0)
                .withNano(0)
                .minusMinutes(first.getMessageTime().getMinute() % 5);

        // 트랙 포인트 생성
        List<VesselTrack.TrackPoint> trackPoints = vesselDataList.stream()
                .map(data -> VesselTrack.TrackPoint.builder()
                        .time(data.getMessageTime())
                        .lat(data.getLat())
                        .lon(data.getLon())
                        .sog(data.getSog())
                        .cog(data.getCog())
                        .heading(data.getHeading())
                        .build())
                .collect(Collectors.toList());

        VesselTrack track = VesselTrack.builder()
                .sigSrcCd(first.getSigSrcCd())
                .targetId(first.getTargetId())
                .timeBucket(timeBucket)
                .trackPoints(trackPoints)
                .pointCount(trackPoints.size())
                .startPosition(VesselTrack.TrackPosition.builder()
                        .lat(first.getLat())
                        .lon(first.getLon())
                        .time(first.getMessageTime())
                        .sog(first.getSog())
                        .build())
                .endPosition(VesselTrack.TrackPosition.builder()
                        .lat(last.getLat())
                        .lon(last.getLon())
                        .time(last.getMessageTime())
                        .sog(last.getSog())
                        .build())
                .build();

        // 거리 계산
        track.setDistanceNm(track.calculateDistance());

        // 속도 통계
        calculateSpeedStatistics(track, vesselDataList);

        // PostGIS LineStringM 생성 (MIGRATION_V2: 전략 패턴 적용)
        if (dualWrite) {
            // Dual Write 모드: 양쪽 모두 저장
            track.setTrackGeom(relativeStrategy.buildLineStringM(trackPoints));
            track.setTrackGeomV2(unixStrategy.buildLineStringM(trackPoints));
            // Dual-write mode: Generated both relative and unix LineStringM
        } else if ("unix".equals(mValueFormat)) {
            // Unix 모드: v2만 저장
            track.setTrackGeomV2(unixStrategy.buildLineStringM(trackPoints));
            // Unix mode: Generated unix LineStringM only
        } else {
            // 기존 모드: v1만 저장
            track.setTrackGeom(relativeStrategy.buildLineStringM(trackPoints));
        }

        // 해구 정보 추가
        addHaeguInfo(track, first);

        // Area 정보 추가 (개선된 메서드)
        addAreaInfo(track);

        return track;
    }

    private void calculateSpeedStatistics(VesselTrack track, List<VesselData> vesselDataList) {
        BigDecimal MAX_SPEED_LIMIT = new BigDecimal("9999.99"); // NUMERIC(6,2) 제한

        // 1. 최대 속도는 SOG 값들 중 최대값 사용
        BigDecimal maxSpeed = vesselDataList.stream()
                .map(VesselData::getSog)
                .filter(Objects::nonNull)
                .filter(speed -> speed.compareTo(BigDecimal.ZERO) >= 0)
                .filter(speed -> speed.compareTo(MAX_SPEED_LIMIT) <= 0)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        track.setMaxSpeed(maxSpeed);

        // 2. 평균 속도는 실제 이동거리와 M값 기반 시간으로 계산
        if (track.getDistanceNm() != null && track.getDistanceNm().compareTo(new BigDecimal("0.1")) > 0) {
            // 거리가 0.1nm 이상일 때만 속도 계산
            // M값 기반 정확한 시간 계산
            long totalSeconds = calculateDurationFromMValues(track);

            if (totalSeconds > 0) {
                // 거리(해리) / 시간(시간) = 속도(노트)
                BigDecimal hours = BigDecimal.valueOf(totalSeconds).divide(BigDecimal.valueOf(3600), 4, BigDecimal.ROUND_HALF_UP);
                BigDecimal avgSpeed = track.getDistanceNm().divide(hours, 2, BigDecimal.ROUND_HALF_UP);
                // NUMERIC(6,2) 제한만 적용 (실제 데이터 특성상 극단값도 유효)
                track.setAvgSpeed(avgSpeed.min(MAX_SPEED_LIMIT));
            } else {
                // 시간차가 없고 거리가 있는 경우는 이상 데이터
                track.setAvgSpeed(BigDecimal.ZERO);
            }
        } else {
            // 거리가 0.1nm 미만이면 정박 중으로 간주
            track.setAvgSpeed(BigDecimal.ZERO);
        }
    }
    
    /**
     * LineStringM의 M값으로부터 실제 경과 시간 계산
     */
    private long calculateDurationFromMValues(VesselTrack track) {
        // WKT에서 M값 추출
        String wkt = track.getTrackGeomV2() != null ? track.getTrackGeomV2() : track.getTrackGeom();
        if (wkt == null || !wkt.contains("LINESTRING M")) {
            // M값이 없으면 기존 방식 폴백
            return java.time.Duration.between(
                    track.getStartPosition().getTime(),
                    track.getEndPosition().getTime()
            ).getSeconds();
        }
        
        try {
            // "LINESTRING M(x y m, x y m, ...)" 파싱
            String coords = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
            String[] points = coords.split(",");
            
            if (points.length == 0) return 0;
            
            // 첫 포인트와 마지막 포인트의 M값 추출
            String[] firstPoint = points[0].trim().split(" ");
            String[] lastPoint = points[points.length - 1].trim().split(" ");
            
            if (firstPoint.length < 3 || lastPoint.length < 3) return 0;
            
            double firstM = Double.parseDouble(firstPoint[2]);
            double lastM = Double.parseDouble(lastPoint[2]);
            
            // Unix timestamp vs 상대시간 구분
            if ("unix".equals(mValueFormat) || track.getTrackGeomV2() != null) {
                // Unix timestamp: 차이가 경과 시간(초)
                return (long)(lastM - firstM);
            } else {
                // 상대시간: 마지막 M값이 경과 시간(초)
                return (long)lastM;
            }
        } catch (Exception e) {
            log.warn("M값 파싱 실패, 기존 방식 사용: {}", e.getMessage());
            return java.time.Duration.between(
                    track.getStartPosition().getTime(),
                    track.getEndPosition().getTime()
            ).getSeconds();
        }
    }

    // MIGRATION_V2: 기존 메서드는 전략 패턴으로 대체됨
    @Deprecated
    private String buildLineStringM(List<VesselTrack.TrackPoint> trackPoints) {
        // 전략 패턴 사용으로 인해 더 이상 직접 사용하지 않음
        return relativeStrategy.buildLineStringM(trackPoints);
    }

    private void addHaeguInfo(VesselTrack track, VesselData vesselData) {
        HaeguGeoUtils.HaeguTileInfo haeguInfo = haeguGeoUtils.getHaeguTileInfo(vesselData.getLat(), vesselData.getLon(), 0);
        if (haeguInfo != null) {
            track.setHaeguNo(haeguInfo.haeguNo);
        }
    }

    // 개선된 area 정보 추가 메서드 - 캐시 활용
    private void addAreaInfo(VesselTrack track) {
        try {
            String startAreaId = areaBoundaryCache.findAreaId(
                track.getStartPosition().getLat(),
                track.getStartPosition().getLon()
            );
            String endAreaId = areaBoundaryCache.findAreaId(
                track.getEndPosition().getLat(),
                track.getEndPosition().getLon()
            );

            if (startAreaId != null) {
                track.setAreaId(startAreaId);
                track.setEntryTime(track.getStartPosition().getTime());

                if (!startAreaId.equals(endAreaId)) {
                    track.setExitTime(track.getEndPosition().getTime());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to add area info for vessel {}: {}", track.getVesselKey(), e.getMessage());
        }
    }

}
