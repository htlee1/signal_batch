// Web Worker for parallel track merging
// Optimized for batch processing

// LineStringM 파싱 (최적화 버전)
function parseLineStringM(wkt, startTime) {
    if (!wkt) return [];
    
    const startIdx = wkt.indexOf('(') + 1;
    const endIdx = wkt.lastIndexOf(')');
    if (startIdx <= 0 || endIdx < startIdx) return [];
    
    const baseTime = typeof startTime === 'string' ? 
        new Date(startTime).getTime() : startTime;
    
    if (isNaN(baseTime)) return [];
    
    const coordsStr = wkt.substring(startIdx, endIdx);
    const points = [];
    
    // 정규식 대신 직접 파싱 (더 빠름)
    const coords = coordsStr.split(',');
    for (let i = 0; i < coords.length; i++) {
        const parts = coords[i].trim().split(' ');
        if (parts.length >= 3) {
            const lon = parseFloat(parts[0]);
            const lat = parseFloat(parts[1]);
            const m = parseFloat(parts[2]);
            
            if (!isNaN(lon) && !isNaN(lat)) {
                points.push({
                    lon: lon,
                    lat: lat,
                    time: baseTime + (m * 1000)
                });
            }
        }
    }
    
    return points;
}

// 배치 병합 처리
function processBatch(batch) {
    const vesselMap = new Map();
    
    for (const track of batch) {
        const vesselKey = `${track.sigSrcCd}_${track.targetId}`;
        
        let vessel = vesselMap.get(vesselKey);
        if (!vessel) {
            vessel = {
                id: vesselKey,
                sigSrcCd: track.sigSrcCd,
                targetId: track.targetId,
                segments: new Map(),
                metadata: {
                    totalDistance: 0,
                    avgSpeed: 0,
                    minTime: Infinity,
                    maxTime: -Infinity
                }
            };
            vesselMap.set(vesselKey, vessel);
        }
        
        // startTime 처리
        const startTime = track.startTime || track.timeBucket;
        if (!startTime) continue;
        
        // 세그먼트 생성
        const segment = {
            timeBucket: track.timeBucket || startTime,
            points: parseLineStringM(track.trackGeom, startTime),
            distance: track.distanceNm,
            avgSpeed: track.avgSpeed
        };
        
        if (segment.points.length > 0) {
            vessel.segments.set(segment.timeBucket, segment);
            
            // 메타데이터 업데이트
            vessel.metadata.totalDistance += segment.distance || 0;
            const firstPoint = segment.points[0];
            const lastPoint = segment.points[segment.points.length - 1];
            
            vessel.metadata.minTime = Math.min(vessel.metadata.minTime, firstPoint.time);
            vessel.metadata.maxTime = Math.max(vessel.metadata.maxTime, lastPoint.time);
        }
    }
    
    // 각 선박의 전체 경로 구성
    const results = [];
    for (const [vesselKey, vessel] of vesselMap) {
        // 세그먼트 정렬 및 병합
        const sortedSegments = Array.from(vessel.segments.values())
            .sort((a, b) => (a.points[0]?.time || 0) - (b.points[0]?.time || 0));
        
        const fullPath = [];
        let lastTime = -Infinity;
        
        for (const seg of sortedSegments) {
            for (const point of seg.points) {
                // 1초 이상 차이나는 경우만 추가 (중복 제거)
                if (point.time - lastTime > 1000) {
                    fullPath.push(point);
                    lastTime = point.time;
                }
            }
        }
        
        // 평균 속도 계산
        const avgSpeeds = sortedSegments
            .map(s => s.avgSpeed)
            .filter(s => s > 0);
        vessel.metadata.avgSpeed = avgSpeeds.length > 0 ? 
            avgSpeeds.reduce((a, b) => a + b, 0) / avgSpeeds.length : 0;
        
        vessel.fullPath = fullPath;
        results.push(vessel);
    }
    
    return results;
}

// Worker 메시지 핸들러
self.addEventListener('message', (e) => {
    const { type, data, id } = e.data;
    
    if (type === 'PROCESS_BATCH') {
        const startTime = performance.now();
        const results = processBatch(data);
        const duration = performance.now() - startTime;
        
        self.postMessage({
            type: 'BATCH_COMPLETE',
            id: id,
            results: results,
            stats: {
                processedTracks: data.length,
                resultVessels: results.length,
                processingTime: duration
            }
        });
    }
});

console.log('Track merge worker ready');
