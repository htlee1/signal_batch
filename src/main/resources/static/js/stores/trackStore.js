// Zustand store for vessel track management (JavaScript version)
// Optimized for handling 20,000-30,000 vessels

// Track store factory
function createTrackStore() {
  let state = {
    // 데이터
    vessels: new Map(),
    totalVessels: 0,
    totalPoints: 0,
    
    // 애니메이션 상태
    animation: {
      playing: false,
      currentTime: 0,
      startTime: 0,
      endTime: 0,
      speed: 1,
      targetFPS: 30
    },
    
    // 성능 설정
    performance: {
      maxRenderVessels: 20000, // 5000 -> 20000으로 증가
      dynamicFPS: true,
      currentFPS: 30,
      targetFPS: 30,
      lastStatsUpdate: 0 // 마지막 통계 업데이트 시간
    },
    
    // 뷰포트 정보
    viewport: {
      bounds: [120, 30, 140, 45],
      zoom: 6
    }
  };
  
  // 리스너들
  const listeners = new Set();
  
  // 상태 업데이트 함수
  function setState(updater) {
    const newState = typeof updater === 'function' ? updater(state) : updater;
    state = { ...state, ...newState };
    listeners.forEach(listener => listener(state));
  }
  
  // LineStringM 파싱
  function parseLineStringM(wkt, startTime) {
    if (!wkt) return [];
    
    const matches = wkt.match(/LINESTRING M\s*\((.*)\)/);
    if (!matches) return [];
    
    // startTime이 ISO string 형식인지 확인
    const baseTime = typeof startTime === 'string' ? 
      new Date(startTime).getTime() : startTime;
    
    if (isNaN(baseTime)) {
      console.error('Invalid startTime:', startTime);
      return [];
    }
    
    try {
      return matches[1].split(',').map(coord => {
        const parts = coord.trim().split(/\s+/);
        const mValue = parseFloat(parts[2] || 0);
        return {
          lon: parseFloat(parts[0]),
          lat: parseFloat(parts[1]),
          time: baseTime + (mValue * 1000) // M값은 초 단위
        };
      }).filter(p => !isNaN(p.lon) && !isNaN(p.lat));
    } catch (error) {
      console.error('Error parsing LineStringM:', error);
      return [];
    }
  }
  
  // 병합 함수
  function mergePathWithDedup(vessel, newSegment) {
    const segment = {
      timeBucket: newSegment.timeBucket || newSegment.startTime,
      points: parseLineStringM(newSegment.trackGeom, newSegment.startTime),
      distance: newSegment.distanceNm,
      avgSpeed: newSegment.avgSpeed
    };
    
    // 세그먼트 저장
    vessel.segments.set(segment.timeBucket, segment);
    
    // 전체 경로 재구성 (시간순 정렬)
    const allPoints = [];
    const sortedSegments = Array.from(vessel.segments.values())
      .sort((a, b) => (a.points[0]?.time || 0) - (b.points[0]?.time || 0));
    
    sortedSegments.forEach(seg => {
      seg.points.forEach(point => {
        // 중복 제거 (1초 이상 차이나는 경우만)
        if (allPoints.length === 0 || 
            Math.abs(point.time - allPoints[allPoints.length - 1].time) > 1000) {
          allPoints.push(point);
        }
      });
    });
    
    vessel.fullPath = allPoints;
    
    // 메타데이터 업데이트
    if (allPoints.length > 0) {
      vessel.metadata.minTime = allPoints[0].time;
      vessel.metadata.maxTime = allPoints[allPoints.length - 1].time;
    }
    
    vessel.metadata.totalDistance += segment.distance;
    const avgSpeeds = sortedSegments.map(s => s.avgSpeed).filter(s => s > 0);
    vessel.metadata.avgSpeed = avgSpeeds.reduce((a, b) => a + b, 0) / (avgSpeeds.length || 1);
  }
  
  // 이진 탐색으로 시간에 해당하는 포인트 찾기
  function findPointAtTime(vessel, targetTime) {
    const points = vessel.fullPath;
    if (!points || points.length === 0) return null;
    
    // 선박이 현재 시간 범위에 있는지 확인
    const firstTime = points[0].time;
    const lastTime = points[points.length - 1].time;
    
    // 시간 범위 밖이면 null 반환 (선박 안 보임)
    if (targetTime < firstTime - 60000 || targetTime > lastTime + 60000) { // 1분 여유
      return null;
    }
    
    if (targetTime <= firstTime) return points[0];
    if (targetTime >= lastTime) return points[points.length - 1];
    
    // 이진 탐색
    let left = 0;
    let right = points.length - 1;
    
    while (left < right - 1) {
      const mid = Math.floor((left + right) / 2);
      if (points[mid].time <= targetTime) {
        left = mid;
      } else {
        right = mid;
      }
    }
    
    // 선형 보간
    const p1 = points[left];
    const p2 = points[right];
    const ratio = (targetTime - p1.time) / (p2.time - p1.time);
    
    return {
      lon: p1.lon + (p2.lon - p1.lon) * ratio,
      lat: p1.lat + (p2.lat - p1.lat) * ratio,
      time: targetTime
    };
  }
  
  // Store API
  return {
    // State getter
    getState: () => state,
    
    // Subscribe to changes
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    
    // Actions
    addProcessedVessel: (vessel) => {
      // Worker에서 처리된 결과 추가
      state.vessels.set(vessel.id, vessel);
      
      // 애니메이션 시간 범위 업데이트
      let animationUpdate = {};
      if (vessel.metadata.minTime !== Infinity && 
          (vessel.metadata.minTime < state.animation.startTime || state.animation.startTime === 0)) {
        animationUpdate.startTime = vessel.metadata.minTime;
        if (state.animation.currentTime === 0) {
          animationUpdate.currentTime = vessel.metadata.minTime;
        }
      }
      if (vessel.metadata.maxTime !== -Infinity && vessel.metadata.maxTime > state.animation.endTime) {
        animationUpdate.endTime = vessel.metadata.maxTime;
      }
      
      // 성능 설정 업데이트
      let performanceUpdate = {};
      if (state.performance.dynamicFPS) {
        const vesselCount = state.vessels.size;
        if (vesselCount > 20000) {
          performanceUpdate.targetFPS = 10;
        } else if (vesselCount > 10000) {
          performanceUpdate.targetFPS = 15;
        } else if (vesselCount > 5000) {
          performanceUpdate.targetFPS = 20;
        } else {
          performanceUpdate.targetFPS = 30;
        }
      }
      
      // 성능 최적화: 통계는 1초에 한 번만 계산
      const now = Date.now();
      const shouldUpdateStats = (now - state.performance.lastStatsUpdate > 1000) || vessel.isLastVessel;
      const totalPoints = shouldUpdateStats ? 
        Array.from(state.vessels.values()).reduce((sum, v) => sum + (v.fullPath?.length || 0), 0) :
        state.totalPoints + (vessel.fullPath?.length || 0);
      
      if (shouldUpdateStats) {
        performanceUpdate.lastStatsUpdate = now;
      }
      
      setState({
        totalVessels: state.vessels.size,
        totalPoints: totalPoints,
        animation: { ...state.animation, ...animationUpdate },
        performance: { ...state.performance, ...performanceUpdate }
      });
    },
    
    mergeTrackData: (data) => {
      const vesselKey = `${data.sigSrcCd}_${data.targetId}`;
      
      let vessel = state.vessels.get(vesselKey);
      if (!vessel) {
        vessel = {
          id: vesselKey,
          sigSrcCd: data.sigSrcCd,
          targetId: data.targetId,
          fullPath: [],
          segments: new Map(),
          metadata: {
            totalDistance: 0,
            avgSpeed: 0,
            minTime: Infinity,
            maxTime: -Infinity
          }
        };
        state.vessels.set(vesselKey, vessel);
      }
      
      // startTime이 없으면 timeBucket 사용
      if (!data.startTime && data.timeBucket) {
        data.startTime = data.timeBucket;
      }
      
      mergePathWithDedup(vessel, data);
      
      // 성능 최적화: 매번 전체 통계를 계산하지 않고 증분만 업데이트
      const newPointsAdded = vessel.fullPath.length;
      
      // 애니메이션 시간 범위 업데이트
      let animationUpdate = {};
      if (vessel.metadata.minTime !== Infinity && 
          (vessel.metadata.minTime < state.animation.startTime || state.animation.startTime === 0)) {
        animationUpdate.startTime = vessel.metadata.minTime;
        // 최초 데이터일 때 currentTime도 설정
        if (state.animation.currentTime === 0 || state.animation.currentTime > vessel.metadata.maxTime) {
          animationUpdate.currentTime = vessel.metadata.minTime;
        }
      }
      if (vessel.metadata.maxTime !== -Infinity && vessel.metadata.maxTime > state.animation.endTime) {
        animationUpdate.endTime = vessel.metadata.maxTime;
      }
      
      // 2-3만 선박 처리를 위한 동적 FPS 조정 (일정 간격으로만)
      let performanceUpdate = {};
      if (state.performance.dynamicFPS && state.vessels.size % 100 === 0) {
        const vesselCount = state.vessels.size;
        if (vesselCount > 20000) {
          performanceUpdate.targetFPS = 10;
        } else if (vesselCount > 10000) {
          performanceUpdate.targetFPS = 15;
        } else if (vesselCount > 5000) {
          performanceUpdate.targetFPS = 20;
        } else {
          performanceUpdate.targetFPS = 30;
        }
      }
      
      // 성능 최적화: 통계는 1초에 한 번만 계산
      const now = Date.now();
      const shouldUpdateStats = (now - state.performance.lastStatsUpdate > 1000) || data.isLastChunk;
      const totalPoints = shouldUpdateStats ? 
        Array.from(state.vessels.values()).reduce((sum, v) => sum + v.fullPath.length, 0) :
        state.totalPoints + newPointsAdded;
      
      if (shouldUpdateStats) {
        performanceUpdate.lastStatsUpdate = now;
      }
      
      setState({
        totalVessels: state.vessels.size,
        totalPoints: totalPoints,
        animation: { ...state.animation, ...animationUpdate },
        performance: { ...state.performance, ...performanceUpdate }
      });
    },
    
    clearTracks: () => {
      state.vessels.clear();
      setState({
        vessels: new Map(),
        totalVessels: 0,
        totalPoints: 0,
        animation: {
          playing: false,
          currentTime: 0,
          startTime: 0,
          endTime: 0,
          speed: 1,
          targetFPS: 30
        }
      });
    },
    
    setAnimationTime: (time) => {
      setState((prevState) => {
        const clampedTime = Math.max(
          prevState.animation.startTime,
          Math.min(prevState.animation.endTime, time)
        );
        return {
          animation: {
            ...prevState.animation,
            currentTime: clampedTime
          }
        };
      });
    },
    
    setAnimationSpeed: (speed) => {
      setState({
        animation: { ...state.animation, speed }
      });
    },
    
    toggleAnimation: () => {
      console.log('toggleAnimation called');
      setState((prevState) => ({
        animation: { ...prevState.animation, playing: !prevState.animation.playing }
      }));
    },
    
    updateViewport: (viewport) => {
      setState({ viewport });
    },
    
    setTargetFPS: (fps) => {
      setState({
        performance: { ...state.performance, targetFPS: fps },
        animation: { ...state.animation, targetFPS: fps }
      });
    },
    
    // 애니메이션 시간 범위 설정
    setAnimationTimeRange: (startTime, endTime) => {
      setState({
        animation: {
          ...state.animation,
          startTime: startTime,
          endTime: endTime,
          currentTime: startTime
        }
      });
    },
    
    // 보이는 선박만 반환 (viewport culling + LOD)
    getVisibleVessels: (includeAllTime = false) => {
      const [minLon, minLat, maxLon, maxLat] = state.viewport.bounds;
      // viewport 버퍼 추가 (1도씩 확장)
      const bufferMinLon = minLon - 1;
      const bufferMaxLon = maxLon + 1;
      const bufferMinLat = minLat - 1;
      const bufferMaxLat = maxLat + 1;
      
      const currentTime = state.animation.currentTime || state.animation.startTime || Date.now();
      
      // 2-3만 선박 처리: 공간 인덱싱 시뮬레이션
      const candidates = [];
      
      for (const [id, vessel] of state.vessels) {
        // 빠른 시간 범위 체크 (느슨하게 변경)
        if (vessel.metadata.minTime === Infinity || vessel.metadata.maxTime === -Infinity) {
        continue;
        }
        
        // includeAllTime이 true면 모든 궤적 포함
        if (includeAllTime) {
          // 궤적이 viewport에 걸치는지 확인
          const inViewport = vessel.fullPath.some(p => 
            p.lon >= bufferMinLon && p.lon <= bufferMaxLon &&
            p.lat >= bufferMinLat && p.lat <= bufferMaxLat
          );
          
          if (inViewport) {
            candidates.push({
              vessel,
              position: vessel.fullPath[0], // 첫 위치 사용
              priority: vessel.metadata.avgSpeed
            });
          }
        } else {
          // 현재 위치 계산
          const pos = findPointAtTime(vessel, currentTime);
          if (!pos) continue;
          
          // viewport 체크 (버퍼 적용)
          if (pos.lon >= bufferMinLon && pos.lon <= bufferMaxLon &&
              pos.lat >= bufferMinLat && pos.lat <= bufferMaxLat) {
            candidates.push({
              vessel,
              position: pos,
              priority: vessel.metadata.avgSpeed // 속도 기준 우선순위
            });
          }
        }
      }
      
      // 최대 렌더링 수 제한
      if (candidates.length > state.performance.maxRenderVessels) {
        // 우선순위 정렬 후 상위 N개만 선택
        candidates.sort((a, b) => b.priority - a.priority);
        return candidates.slice(0, state.performance.maxRenderVessels).map(c => c.vessel);
      }
      
      return candidates.map(c => c.vessel);
    },
    
    // 특정 시간의 선박 위치 계산
    getVesselPositionAtTime: (vesselId, time) => {
      const vessel = state.vessels.get(vesselId);
      if (!vessel) return null;
      
      return findPointAtTime(vessel, time || state.animation.currentTime);
    },
    
    // 현재 시간의 모든 선박 위치 (성능 최적화)
    getAllVesselPositions: () => {
      const positions = [];
      const visibleVessels = state.getVisibleVessels();
      const currentTime = state.animation.currentTime;
      
      for (const vessel of visibleVessels) {
        const pos = findPointAtTime(vessel, currentTime);
        if (pos) {
          positions.push({
            id: vessel.id,
            position: [pos.lon, pos.lat],
            speed: vessel.metadata.avgSpeed,
            bearing: 0 // TODO: calculate bearing
          });
        }
      }
      
      return positions;
    },
    
    // 성능 통계
    getPerformanceStats: () => {
      const store = window.trackStore;
      return {
        totalVessels: state.totalVessels,
        totalPoints: state.totalPoints,
        visibleVessels: store.getVisibleVessels().length,
        targetFPS: state.performance.targetFPS,
        maxRenderVessels: state.performance.maxRenderVessels
      };
    }
  };
}

// 전역 store 인스턴스
window.trackStore = createTrackStore();
