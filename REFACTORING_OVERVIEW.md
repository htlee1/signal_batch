# WebSocket 청크 스트리밍 API 구조 및 동작 방식

## 1. 시스템 개요

### 1.1 목적
대용량 선박 항적 데이터를 효율적으로 스트리밍하여 프론트엔드 렌더링 성능 최적화

### 1.2 핵심 전략
- 시간 범위별 적절한 테이블 선택 (5min/hourly/daily)
- 6시간 단위 데이터 병합
- 거리/시간 기반 항적 간소화
- 메시지 크기 기반 청크 분할


## 2. 주요 컴포넌트

### 2.1 ChunkedTrackStreamingService
**위치**: `gc.mda.signal_batch.service.ChunkedTrackStreamingService`

#### 핵심 메서드

##### streamChunkedTracks()
```java
public void streamChunkedTracks(TrackQueryRequest request, String queryId, 
                               Consumer<TrackChunkResponse> chunkConsumer,
                               Consumer<QueryStatusUpdate> statusConsumer)
```
- **역할**: 비동기 스트리밍 진입점
- **동작**:
  1. 시간 범위를 테이블 전략별로 분할
  2. Daily → Hourly → 5min 순서로 처리
  3. 6시간 단위로 데이터 그룹화 및 병합
  4. 청크 생성 및 전송

##### processTableRangeWithBaseTime()
```java
private List<CompactVesselTrack> processTableRangeWithBaseTime(
    TrackQueryRequest request, TableStrategy strategy, 
    TimeRange range, LocalDateTime dayBaseTime)
```
- **역할**: 테이블 범위 처리 및 간소화 적용
- **간소화 기준**:
  - 5min: 1km(0.54nm) 또는 30분 간격
  - Hourly: 2km(1.08nm) 또는 60분 간격
  - 저속 선박(<5knots): 2.8km(1.5nm) 또는 45분 간격

##### splitByMessageSize()
```java
private List<List<CompactVesselTrack>> splitByMessageSize(List<CompactVesselTrack> tracks)
```
- **역할**: 메시지 크기 기반 청크 분할
- **기준**: 768KB/청크

### 2.2 데이터 구조

#### VesselAccumulator (내부 클래스)
```java
private static class VesselAccumulator {
    String sigSrcCd;
    String targetId;
    List<double[]> geometry = new ArrayList<>(500);
    List<String> timestamps = new ArrayList<>(500);
    List<Double> speeds = new ArrayList<>(500);
    double totalDistance = 0;
    double maxSpeed = 0;
    int pointCount = 0;
}
```
- **역할**: 선박별 데이터 누적 (성능 최적화)

#### CompactVesselTrack (DTO)
**위치**: `gc.mda.signal_batch.dto.CompactVesselTrack`
```java
{
    vesselId: "sig_src_cd_target_id",
    geometry: [[lon,lat], ...],
    timestamps: ["2025-07-30 14:20:00", ...],
    speeds: [12.5, 13.2, ...],
    totalDistance: 45.67,
    avgSpeed: 14.2,  // 6시간 청크별 평균
    maxSpeed: 18.5,
    pointCount: 1500
}
```

## 3. 처리 흐름

### 3.1 테이블 선택 로직
```
현재시간 기준:
- 1시간 이내 → 5min 테이블
- 1-24시간 → hourly 테이블  
- 24시간 이상 → daily 테이블
```

### 3.2 데이터 처리 단계
1. **시간 범위 분할**: `splitTimeRangeByStrategy()`
2. **테이블별 처리**:
   - Daily: 날짜별 직접 처리
   - Hourly/5min: 6시간 단위 그룹화
3. **데이터 병합**: `VesselAccumulator`에 누적
4. **간소화 적용**: 거리/시간 기반 포인트 필터링
5. **평균속도 계산**: 6시간 청크별 거리/시간
6. **청크 분할**: 768KB 단위로 분할
7. **스트리밍 전송**: WebSocket으로 전송

### 3.3 성능 최적화

#### 메모리 관리
- HashMap 초기 크기: 20,000
- ArrayList 초기 용량: 500
- VesselAccumulator로 Builder 패턴 대체

#### 적응형 처리
```java
// FetchSize 동적 조정
int adaptiveFetchSize = Math.max(1000, 
    Math.min(10000, 50000 / Math.max(1, vesselMap.size() / 1000)));

// 대기 시간 조정
int waitTime = uniqueVesselIds.size() > 10000 ? 50 : 10;
```

## 4. PostGIS 간소화 (TrackSimplificationStrategy)
**위치**: `gc.mda.signal_batch.service.simplification.TrackSimplificationStrategy`

### SimplificationLevel
```java
LIGHT(0.0001)      // 기본
MODERATE(0.0005)   // 중간
HEAVY(0.001)       // 강함
EXTREME(0.002)     // 극도
```

## 5. 컨트롤러 통합
**위치**: `gc.mda.signal_batch.controller.VesselTrackWebSocketController`

### STOMP 엔드포인트
- 연결: `ws://server:8090/ws-tracks`
- 쿼리: `/app/tracks/query/chunked`
- 구독: `/user/queue/tracks/chunks/{queryId}`

## 6. 성능 특성

### 6.1 처리 시간
- 6시간 데이터: 25초 (98만→62만 포인트)
- 간소화율: 약 37%

### 6.2 병목 현상
- 초반 20만 포인트: 5초
- 중반 45만 포인트: 20초  
- 후반 62만 포인트: 70초
- 원인: 메모리 누적, GC 압박

### 6.3 평균속도 계산
- 범위: 6시간 청크별
- 방식: totalDistance / 청크시간
- 이유: 스트리밍 구조상 전체 데이터 보관 불가

## 7. 클라이언트 통합

### 7.1 데이터 수신
```javascript
// WebSocket 메시지 핸들러
function handleChunk(chunk) {
    chunk.compactTracks.forEach(track => {
        // 선박별 데이터 병합
        vesselDataMap[track.vesselId].geometry.push(...track.geometry);
        vesselDataMap[track.vesselId].timestamps.push(...track.timestamps);
    });
}
```

### 7.2 진행률 표시
- 청크 인덱스 기반
- 마지막 청크에서 totalChunks 확정

---
최종 수정: 2025-07-30 14:30



# 성능 최적화 개선 작업

## 1. 프론트엔드 청크 병합 최적화 (우선순위: 높음)

### 현재 문제점
- 30만 포인트 이상 데이터 처리 시 병합 과정에서 급격한 성능 저하
- 선박별로 매 청크마다 배열 concat/push 연산으로 메모리 재할당 발생

### 개선 방안
1. **청크 단위 가상 병합 방식**
    - 청크 데이터를 병합하지 않고 원본 그대로 보관
    - 렌더링 시점에만 flatMap으로 가상 결합
    - 메모리 재할당 없이 참조만 결합

2. **구현 상세**
   ```javascript
   // 현재: 매 청크마다 실제 병합
   vessel.geometry.push(...newPoints); // 느림
   
   // 개선: 청크 단위로 보관
   vessel.chunks.push({geometry, timestamps}); // 빠름
   
   // 렌더링 시에만 가상 결합
   const path = vessel.chunks.flatMap(c => c.geometry);
   ```

3. **예상 효과**
    - 병합 시간 90% 단축
    - 메모리 사용량 50% 감소
    - 사용자 체감 지연 해소

### 작업 계획
1. VesselTrackStore 리팩토링
2. 청크 기반 데이터 구조로 변경
3. TripsLayer 렌더링 로직 수정
4. 성능 테스트 및 검증

## 2. 백엔드 추가 최적화 (우선순위: 중간)

### 줌 레벨 기반 간소화
- 프론트엔드 줌 레벨을 파라미터로 받아 동적 간소화
- 원거리 뷰: 극도 간소화 (90% 포인트 제거)
- 근거리 뷰: 경량 간소화 (30% 포인트 제거)

### 구현 상세

#### 2.1. TrackQueryRequest DTO 수정
```java
public class TrackQueryRequest {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ViewportFilter viewport;
    private Integer zoomLevel;  // 추가: 맵 줌 레벨 (0-22)
    // ... 기타 필드
}
```

#### 2.2. SimplificationLevel 확장
```java
public enum SimplificationLevel {
    NONE(0),           // 간소화 없음
    LIGHT(0.0001),     // 30% 제거
    MODERATE(0.0005),  // 50% 제거
    HEAVY(0.001),      // 70% 제거
    VERY_HEAVY(0.0015),// 80% 제거 (추가)
    EXTREME(0.002)     // 90% 제거
}
```

#### 2.3. 간소화 레벨 결정 로직 개선
```java
private SimplificationLevel determineSimplificationLevel(
    TrackQueryRequest request, TimeChunk chunk) {
    
    Integer zoom = request.getZoomLevel();
    if (zoom == null) zoom = 10; // 기본값
    
    // 줌 레벨 우선 적용
    if (zoom < 6) return SimplificationLevel.EXTREME;      // 원거리: 90% 제거
    if (zoom < 8) return SimplificationLevel.VERY_HEAVY;  // 80% 제거  
    if (zoom < 10) return SimplificationLevel.HEAVY;      // 70% 제거
    if (zoom < 12) return SimplificationLevel.MODERATE;   // 50% 제거
    
    // 시간 범위는 보조 기준으로 사용
    Duration duration = Duration.between(chunk.start, chunk.end);
    if (duration.toHours() > 24 && zoom >= 12) {
        // 긴 시간 범위는 줌인 상태에서도 추가 간소화
        return SimplificationLevel.MODERATE;
    }
    
    return SimplificationLevel.LIGHT; // 근거리: 30% 제거
}
```

#### 2.4. 추가 포인트 샘플링 (processTableRangeWithBaseTime 메서드)
```java
// 줌 레벨에 따른 추가 샘플링
if (request.getZoomLevel() != null && request.getZoomLevel() < 10) {
    int sampleRate = request.getZoomLevel() < 6 ? 10 : 
                     request.getZoomLevel() < 8 ? 5 : 2;
    
    // 매 N번째 포인트만 유지
    List<double[]> sampledGeometry = new ArrayList<>();
    List<String> sampledTimestamps = new ArrayList<>();
    List<Double> sampledSpeeds = new ArrayList<>();
    
    for (int i = 0; i < simplifiedGeometry.size(); i++) {
        if (i % sampleRate == 0 || i == simplifiedGeometry.size() - 1) {
            sampledGeometry.add(simplifiedGeometry.get(i));
            sampledTimestamps.add(simplifiedTimestamps.get(i));
            sampledSpeeds.add(simplifiedSpeeds.get(i));
        }
    }
    
    track.setGeometry(sampledGeometry);
    track.setTimestamps(sampledTimestamps);
    track.setSpeeds(sampledSpeeds);
    track.setPointCount(sampledGeometry.size());
}
```

#### 2.5. 프론트엔드 변경사항
```javascript
function startQuery() {
    const request = {
        startTime: startTimeValue,
        endTime: endTimeValue,
        viewport: {
            minLon: parseFloat(document.getElementById('minLon').value),
            maxLon: parseFloat(document.getElementById('maxLon').value),
            minLat: parseFloat(document.getElementById('minLat').value),
            maxLat: parseFloat(document.getElementById('maxLat').value)
        },
        zoomLevel: Math.floor(map.getZoom()), // 줌 레벨 추가
        chunkedMode: true,
        chunkSize: 20000
    };
    // ...
}

// 맵 줌 변경 시 재쿼리
map.on('zoomend', () => {
    const currentZoom = Math.floor(map.getZoom());
    const prevZoom = request.zoomLevel;
    
    // 줌 레벨이 2단계 이상 변경되면 재쿼리
    if (Math.abs(currentZoom - prevZoom) >= 2) {
        console.log(`Zoom changed: ${prevZoom} -> ${currentZoom}, requerying...`);
        startQuery(); // 새로운 줌 레벨로 재쿼리
    }
});
```

### 예상 효과
- **줌 0-5**: 10% 포인트만 전송 (90% 감소)
- **줌 6-7**: 20% 포인트만 전송 (80% 감소)
- **줌 8-9**: 30% 포인트만 전송 (70% 감소)
- **줌 10-11**: 50% 포인트만 전송 (50% 감소)
- **줌 12+**: 70% 포인트 전송 (30% 감소)

### 성능 개선 예상치
- 30만 포인트 → 줌 8에서 9만 포인트로 감소
- 네트워크 트래픽 70% 감소
- 프론트엔드 처리 시간 70% 단축

## 3. 프로그레시브 렌더링 (우선순위: 낮음)

### 단계별 렌더링
- 화면 내 선박 우선 렌더링
- requestAnimationFrame으로 배치 처리
- 사용자 인터랙션 중 렌더링 일시 중단
