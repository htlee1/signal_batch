# Vessel Batch Aggregation System Overview
*Version 2.0 - Unix Timestamp Migration Complete*  
*Updated: 2025-08-12*

## 시스템 개요

### 목적
실시간으로 수집되는 대용량 선박 위치 데이터를 계층적으로 집계하여 빠른 조회 성능을 제공하는 배치 처리 시스템

### 핵심 기능
1. **실시간 데이터 집계**: 5분 단위로 선박 위치/항적 집계
2. **계층적 집계**: 5분 → 1시간 → 1일 단계적 집계
3. **공간 기반 집계**: 해구(대해구/소해구) 및 사용자 정의 영역별 집계
4. **항적 데이터 관리**: LineStringM 형식의 시공간 항적 저장 (Unix timestamp M값)
5. **비정상 항적 검출**: 물리적 불가능 항적 자동 필터링
6. **과거 항적 조회 및 리플레이**: WebSocket API를 통한 실시간 스트리밍

## 시스템 아키텍처

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   CollectDB     │────▶│  Spring Batch    │────▶│    QueryDB      │
│ (실시간 데이터)  │     │  (집계 처리)     │     │  (집계 데이터)  │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌──────────────────┐
                        │    BatchDB       │
                        │ (메타데이터)     │
                        └──────────────────┘
```

## 주요 변경사항 (2025-08-12 완료)

### ✅ Unix Timestamp 마이그레이션 완료
- **M값 저장 방식**: 상대시간(0, 60, 120...) → Unix timestamp (1754992200...)
- **Timezone 처리**: KST LocalDateTime을 올바른 UTC epoch로 변환
- **데이터 마이그레이션**: 기존 데이터 -32400초(9시간) 보정 완료 (collectDB의 messageTime이 withoutTimezone의 KST임)
- **컬럼 통합**: track_geom_v2 → track_geom 전환 완료

### ✅ 성능 개선 결과
- **Hourly 배치**: 25% 성능 향상 (M값 재계산 제거)
- **Daily 배치**: 30% 성능 향상 (재계산 로직 제거)
- **WebSocket API**: 80% 응답속도 개선 (timestamp 변환 제거)
- **JSON 응답**: 15% 크기 감소 (숫자 배열)
- **코드 라인**: 30% 감소 (중복 로직 제거)

### ✅ 코드 정리 완료
- **제거된 컴포넌트**:
  - RelativeTimeStrategy (상대시간 전략)
  - MValueStrategy 인터페이스
  - LineStringMUtils (M값 재계산 유틸리티)
  - dual-write 관련 모든 코드
- **단순화된 처리**:
  - UnixTimestampStrategy만 사용
  - M값 재계산 없이 직접 병합
  - 설정 파일 간소화

## 데이터 처리 흐름

### 1. 5분 단위 처리
```
원본 데이터 (sig_test) 
    ├─▶ vesselAggregationJob (위치 집계)
    │   ├─▶ t_vessel_latest_position (최신 위치)
    │   ├─▶ t_tile_summary (타일별 집계)
    │   └─▶ t_area_statistics (영역별 통계)
    │
    └─▶ vesselTrackAggregationJob (항적 집계)
        ├─▶ t_vessel_tracks_5min (전체 항적)
        ├─▶ t_grid_vessel_tracks (해구별 항적)
        └─▶ t_area_vessel_tracks (영역별 항적)
```

### 2. 계층적 집계 (Hourly/Daily)
```
5분 데이터 (Unix timestamp M값)
    └─▶ hourlyAggregationJob (매시 10분)
        └─▶ 1시간 데이터 (M값 그대로 유지)
            └─▶ dailyAggregationJob (매일 01:00)
                └─▶ 1일 데이터 (M값 그대로 유지)
```

## LineStringM 데이터 구조

### M값 형식 (Unix Timestamp)
```sql
-- 예시: LINESTRING M(lon lat epoch, ...)
LINESTRING M(126.155848 34.252045 1754960110, 126.154683 34.251877 1754960130)

-- M값 의미
-- 1754960110 = 2025-08-12 09:55:10 UTC (= 2025-08-12 18:55:10 KST) time_bucket은 withouttimezone KST 이므로 9시간 차이가 나면 정상
```

### Timezone 처리
```java
// UnixTimestampStrategy.java
private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

// KST LocalDateTime → UTC epoch 변환
long unixTimestamp = ZonedDateTime.of(point.getTime(), KST_ZONE).toEpochSecond();
```

## 주요 테이블 구조

### 항적 테이블 계층
| 집계 단위 | 전체 항적 | 해구별 항적 | 영역별 항적 |
|-----------|-----------|-------------|-------------|
| 5분 | t_vessel_tracks_5min | t_grid_vessel_tracks | t_area_vessel_tracks |
| 1시간 | t_vessel_tracks_hourly | t_grid_tracks_summary_hourly | t_area_tracks_summary_hourly |
| 1일 | t_vessel_tracks_daily | t_grid_tracks_summary_daily | t_area_tracks_summary_daily |

### 컬럼 구조 (통합)
```sql
-- 모든 항적 테이블 공통
track_geom        geometry(LineStringM, 4326)  -- Unix timestamp M값
distance_nm       NUMERIC(10,2)    -- 이동거리
avg_speed         NUMERIC(6,2)     -- 평균속도
max_speed         NUMERIC(6,2)     -- 최대속도
point_count       INTEGER          -- 포인트 수
```

## Job 실행 스케줄

| Job | 실행 주기 | 실행 시간 | 처리 데이터 |
|-----|----------|-----------|-------------|
| vesselAggregationJob | 5분 | 3, 8, 13, 18... | 최근 5분 위치 |
| vesselTrackAggregationJob | 5분 | 4, 9, 14, 19... | 최근 5분 항적 |
| hourlyAggregationJob | 1시간 | 매시 10분 | 이전 시간 데이터 |
| dailyAggregationJob | 1일 | 매일 01:00 | 전일 데이터 |

## 성능 최적화 전략

### 1. Unix Timestamp 기반 최적화
- **M값 재계산 제거**: 집계 시 M값 그대로 유지
- **직접 병합**: Unix timestamp로 시간 계산 단순화
- **변환 제거**: WebSocket API에서 timestamp 변환 불필요

### 2. 메모리 기반 처리
- VesselDataHolder를 통한 배치 내 데이터 공유
- InMemoryReader로 DB 쿼리 최소화
- 해구 경계 메모리 캐싱

### 3. 병렬 처리
- 파티션 단위 병렬 실행
- Bulk Insert/Copy 활용
- WebSocket 병렬 쿼리 처리

### 4. 공간 인덱싱
- PostGIS GIST 인덱스 활용
- 시공간 복합 인덱싱

### 5. 항적 간소화
- **Hourly 집계**: 10m 이내 이동 생략, 최대 10분 간격
- **Daily 집계**: 20m 이내 이동 생략, 최대 30분 간격
- Unix timestamp M값 유지하면서 포인트 최적화

## WebSocket API

### 항적 스트리밍
- **프로토콜**: STOMP over WebSocket
- **엔드포인트**: `/ws/tracks`
- **성능**: 30일 데이터 10초 이내 스트리밍
- **데이터 형식**: 압축된 배열 형태로 전송 (파싱 부하 최소화)

### 응답 형식 (CompactVesselTrack)
```json
{
  "vesselId": "000001_440308230",
  "sigSrcCd": "000001",
  "targetId": "440308230",
  "geometry": [
    [126.155848, 34.252045],
    [126.154683, 34.251877],
    [126.152953, 34.251877]
  ],
  "timestamps": [
    "1754960110",
    "1754960130",
    "1754960159"
  ],
  "speeds": [
    12.5,
    13.2,
    12.8
  ],
  "totalDistance": 0.5,
  "avgSpeed": 10.2,
  "maxSpeed": 13.2,
  "pointCount": 3,
  "shipName": "VESSEL NAME",
  "shipType": "30",
  "shipKindCode": "000020"
}
```

### 데이터 압축 효과
- **기존**: LineStringM WKT 파싱 필요
- **개선**: 배열 직접 처리로 파싱 오버헤드 제거
- **Unix timestamp**: String 배열로 전송 (호환성 유지)
- **메모리 효율**: 청크당 최대 1MB로 제한

### API 동작 흐름

#### 1. 쿼리 시작 흐름
```
Client → STOMP SEND /app/tracks/query
         ↓
StompTrackController.startTrackQuery()
  - @MessageMapping("/tracks/query")
  - 파라미터: TrackQueryRequest (startTime, endTime, viewport, filters 등)
  - 쿼리 ID 생성, 세션 관리
         ↓
ChunkedTrackStreamingService.streamChunkedTracks() [비동기]
  - 청크 모드로 데이터 처리
  - 파라미터: request, queryId, chunkConsumer, statusConsumer
         ↓
processTableRange() [병렬 처리]
  - 테이블별(5min/hourly/daily) 데이터 조회
  - SQL: ST_AsText(track_geom) 사용
  - LineStringM → 배열 변환
         ↓
parseLineStringM()
  - WKT → Coordinate 배열 파싱
  - M값(Unix timestamp) 추출
         ↓
CompactVesselTrack 생성
  - geometry: [[lon,lat],...]
  - timestamps: ["epoch",...] (String 배열)
  - speeds: SOG 값 배열
         ↓
Client ← STOMP MESSAGE /user/queue/tracks/chunk
```

#### 2. 주요 클래스 및 메소드

| 클래스 | 메소드 | 역할 | 파라미터 |
|--------|--------|------|----------|
| **StompTrackController** | startTrackQuery() | WebSocket 엔트리포인트 | TrackQueryRequest, sessionId |
| | cancelQuery() | 진행중인 쿼리 취소 | queryId, sessionId |
| **ChunkedTrackStreamingService** | streamChunkedTracks() | 비동기 스트리밍 시작 | request, queryId, consumers |
| | processTableRange() | 테이블별 데이터 처리 | table, timeRange, filters |
| | parseLineStringM() | WKT → 배열 변환 | lineStringWKT |
| | getVesselInfo() | 선박 정보 조회(캐시) | sigSrcCd, targetId |
| **TrackQueryRequest** | - | 쿼리 파라미터 DTO | startTime, endTime, viewport, vesselIds, simplificationMode |
| **CompactVesselTrack** | - | 응답 DTO | geometry[], timestamps[], speeds[] |
| **VesselAccumulator** | - | 선박별 데이터 누적 | 내부 클래스 |

#### 3. 데이터 처리 상세

##### LineStringM 파싱 (parseLineStringM)
```java
// 입력: "LINESTRING M(126.15 34.25 1754960110, ...)"
// 처리:
1. WKTReader로 LineString 파싱
2. Coordinate 배열 추출
3. M값(Unix timestamp) → String 변환
4. 배열 형태로 분리:
   - geometry: [lon, lat]
   - timestamps: "epoch"
   - speeds: SOG 값
```

##### 테이블 선택 로직
```java
// 시간 범위에 따른 자동 테이블 선택
- 1시간 이내 (현재 시간 기준 00분까지, 현재시간보다 이전 시간이 조회 대상일 경우 탐색 X): t_vessel_tracks_5min
- 1일 이내 (현재 날짜 기준 00시까지, 현재날짜보다 이전 날짜가 조회 대상일 경우 탐색 X): t_vessel_tracks_hourly  
- 1일 초과 (조회범위가 '오늘' 이전일 경우) : t_vessel_tracks_daily
```

##### 간소화 전략
```java
SimplificationLevel 결정:
 - NONE(1.0, 0.0),           // 원본 (간소화 없음)
 - MINIMAL(0.9, 0.00001),    // 최소 간소화 (90% 유지)
 - LIGHT(0.75, 0.0001),      // 경량 간소화 (75% 유지)
 - MODERATE(0.5, 0.0005),    // 중간 간소화 (50% 유지) - 0.001 -> 0.0005
 - HEAVY(0.25, 0.001),       // 고도 간소화 (25% 유지) - 0.01 -> 0.001
 - VERY_HEAVY(0.2, 0.0015),  // 매우 강한 간소화 (20% 유지)
 - EXTREME(0.1, 0.002)      // 극도 간소화 (10% 유지) - 0.1 -> 0.002
```

#### 4. 성능 최적화 포인트
- **병렬 처리**: ExecutorService(10 스레드)
- **청크 크기**: 최대 1MB/청크, 20,000 트랙/청크
- **백프레셔**: 버퍼 50MB 제한, 동적 청크 크기 조정
- **선박 정보 캐시**: 1시간 TTL, ConcurrentHashMap
- **M값 처리**: Unix timestamp 직접 사용 (변환 없음)

## 모니터링 및 운영

### 대시보드
- **URL**: http://10.26.252.48:8090/static/admin/batch-admin.html
- **주요 기능**:
  - Dashboard: 실시간 현황 및 통계
  - Job Management: 배치 Job 관리
  - Execution History: 실행 이력 조회
  - GIS Monitoring: 해구/영역별 선박 분포
  - WebSocket Test: 항적 스트리밍 테스트
  - Abnormal Tracks: 비정상 항적 모니터링

### 주요 메트릭 (개선 후)
- **처리량**: 5분당 약 25,000건
- **처리 시간**:
  - 5분 배치: 25-30초 (이전 30-40초)
  - 1시간 배치: 1.5-2분 (이전 2-3분)
  - 1일 배치: 7-10분 (이전 10-15분)
- **메모리**: 400-500MB (이전 500-600MB)
- **저장 용량**: 일 2GB 증가 (15% 감소)

## 비정상 항적 검출

### 검출 기준
- **5분 집계**: 
  - 선박: 100 knots/10nm 초과
  - 항공기: 300 knots/30nm 초과
- **Hourly/Daily**: Bucket 간 연결점 검사
- **처리**: t_abnormal_tracks 별도 저장

## 시스템 설정

### application-dev.yml (개발중)
```yaml
vessel:
  batch:
    chunk-size: 5000
    page-size: 10000
```

## 프로젝트 구조 (정리 완료)
```
signal_batch/
├── src/main/java/gc/mda/signal_batch/ #하위 패키지가 존재하는 경우가 있으니 탐색 시 유의
│   ├── common/          # 공통 컴포넌트
│   ├── config/          # 설정 클래스
│   ├── controller/      # REST/WebSocket API
│   ├── job/            # 배치 Job 설정
│   ├── model/          # 도메인 모델
│   ├── processor/      # 데이터 처리 (단순화)
│   ├── reader/         # 데이터 읽기
│   ├── service/        # 비즈니스 로직
│   ├── writer/         # 데이터 쓰기 (단순화)
│   ├── util/           # 유틸리티 (간소화)
│   └── migration/      
│       └── unix_timestamp/
│           └── strategy/
│               └── UnixTimestampStrategy.java  # 유일한 전략
```

## 완료된 마이그레이션 체크리스트

### Phase 1: Unix Timestamp 전환 ✅
- [x] track_geom_v2 컬럼 추가
- [x] Unix timestamp 전략 구현
- [x] Dual-write 모드 구현 및 테스트
- [x] 성능 측정 완료

### Phase 2: 데이터 마이그레이션 ✅
- [x] 기존 데이터 M값 보정 (-32400초)
- [x] Timezone 문제 해결 (KST → UTC epoch)
- [x] 전체 테이블 마이그레이션 완료

### Phase 3: 코드 정리 ✅
- [x] dual-write 비활성화
- [x] 레거시 코드 제거
- [x] 설정 파일 정리
- [x] track_geom_v2 → track_geom 전환

### Phase 4: 검증 ✅
- [x] WebSocket API 정상 동작
- [x] 시간대 정확성 검증
- [x] 성능 개선 확인
- [x] 모든 배치 Job 정상 실행

## 향후 계획

### 단기 (2025 Q3)
- [ ] 파티션 관리 자동화
- [ ] 실시간 스트리밍 처리 고도화
- [ ] 항적 간소화 알고리즘 개선

### 중기 (2025 Q4)
- [ ] 예측 분석 (항로 예측, 패턴 학습)
- [ ] 다중 데이터 소스 통합
- [ ] GraphQL API 추가

### 장기 (2026)
- [ ] 글로벌 확장 (다중 리전)
- [ ] 실시간 이상 탐지 AI
- [ ] 자동 스케일링 구현

---

## 문서 이력
- 2025-08-12: Unix Timestamp 마이그레이션 완료, 성능 개선 반영
- 2025-08-07: 초기 시스템 상태 문서화
- 2025-07-29: 항적 간소화 및 비정상 항적 검출 추가
- 2025-07-21: WebSocket API 구현
