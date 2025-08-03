package gc.mda.signal_batch.processor;

import gc.mda.signal_batch.common.AreaBoundaryCache;
import gc.mda.signal_batch.model.VesselData;
import gc.mda.signal_batch.model.VesselTrack;
import gc.mda.signal_batch.util.HaeguGeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

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

        // PostGIS LineStringM 생성
        track.setTrackGeom(buildLineStringM(trackPoints));

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

        // 2. 평균 속도는 실제 이동거리와 시간 기반으로 계산
        if (track.getDistanceNm() != null && track.getDistanceNm().compareTo(new BigDecimal("0.1")) > 0) {
            // 거리가 0.1nm 이상일 때만 속도 계산
            long totalSeconds = java.time.Duration.between(
                    track.getStartPosition().getTime(),
                    track.getEndPosition().getTime()
            ).getSeconds();

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

    private String buildLineStringM(List<VesselTrack.TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return null;
        }

        // 단일 포인트인 경우 동일한 포인트를 2개로 복사
        if (trackPoints.size() == 1) {
            VesselTrack.TrackPoint point = trackPoints.get(0);
            trackPoints = Arrays.asList(point, point);
        }

        // LINESTRING M (lon lat time) 형식
        // 시간은 epoch seconds로 변환
        LocalDateTime baseTime = trackPoints.get(0).getTime();

        StringBuilder wkt = new StringBuilder("LINESTRING M(");
        for (int i = 0; i < trackPoints.size(); i++) {
            VesselTrack.TrackPoint point = trackPoints.get(i);
            if (i > 0) wkt.append(", ");

            long secondsFromBase = java.time.Duration.between(baseTime, point.getTime()).getSeconds();
            wkt.append(String.format("%.6f %.6f %d",
                    point.getLon(), point.getLat(), secondsFromBase));
        }
        wkt.append(")");

        return wkt.toString();
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
