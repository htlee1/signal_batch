package gc.mda.signal_batch.controller;

import gc.mda.signal_batch.dto.GisBoundaryResponse;
import gc.mda.signal_batch.dto.VesselStatsResponse;
import gc.mda.signal_batch.dto.TrackResponse;
import gc.mda.signal_batch.dto.VesselTracksRequest;
import gc.mda.signal_batch.dto.CompactVesselTrack;
import gc.mda.signal_batch.service.GisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "항적 조회 API", description = "해구 및 영역별 선박 항적 조회 및 통계 API")
public class GisController {
    
    private final GisService gisService;
    
    @GetMapping("/haegu/boundaries")
    @Operation(summary = "해구 경계 조회", description = "모든 해구의 경계 정보를 GeoJSON 형식으로 반환")
    public List<GisBoundaryResponse> getHaeguBoundaries() {
        return gisService.getHaeguBoundaries();
    }
    
    @GetMapping("/haegu/vessel-stats")
    @Operation(summary = "해구별 선박 통계", description = "지정된 시간 동안의 해구별 선박 통계")
    public Map<Integer, VesselStatsResponse> getHaeguVesselStats(
            @RequestParam(defaultValue = "60") int minutes) {
        return gisService.getHaeguVesselStats(minutes);
    }
    
    @GetMapping("/areas/boundaries")
    @Operation(summary = "사용자 정의 영역 경계 조회", description = "모든 사용자 정의 영역의 경계 정보")
    public List<GisBoundaryResponse> getAreaBoundaries() {
        return gisService.getAreaBoundaries();
    }
    
    @GetMapping("/areas/vessel-stats")
    @Operation(summary = "영역별 선박 통계", description = "지정된 시간 동안의 영역별 선박 통계")
    public Map<String, VesselStatsResponse> getAreaVesselStats(
            @RequestParam(defaultValue = "60") int minutes) {
        return gisService.getAreaVesselStats(minutes);
    }
    
    @GetMapping("/tracks/haegu/{haeguNo}")
    @Operation(summary = "해구별 선박 항적", description = "특정 해구의 선박 항적 조회")
    public List<TrackResponse> getHaeguTracks(
            @PathVariable Integer haeguNo,
            @RequestParam(defaultValue = "60") int minutes) {
        return gisService.getHaeguTracks(haeguNo, minutes);
    }
    
    @GetMapping("/tracks/area/{areaId}")
    @Operation(summary = "영역별 선박 항적", description = "특정 영역의 선박 항적 조회")
    public List<TrackResponse> getAreaTracks(
            @PathVariable String areaId,
            @RequestParam(defaultValue = "60") int minutes) {
        return gisService.getAreaTracks(areaId, minutes);
    }
    
    @PostMapping("/tracks/vessels")
    @Operation(summary = "선박별 항적 조회", description = "지정된 선박들의 항적을 조회합니다.")
    public List<CompactVesselTrack> getVesselTracks(
            @RequestBody VesselTracksRequest request) {
        return gisService.getVesselTracks(request);
    }
}
