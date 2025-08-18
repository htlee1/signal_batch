# Sequential Passage API Documentation
*순차 구역 통과 선박 검출 API*  
*Version 1.0 - 2025-08-12*

## API 개요

### 목적
지정된 구역(해구/사용자정의영역)을 순차적으로 통과한 선박을 검출하고 통계를 제공하는 최적화된 API

### 핵심 기능
1. **순차 통과 검출**: 3개 구역을 지정된 순서대로 통과한 선박 조회 (**현재 3개 고정**)
2. **전체 구역 통과**: 순서 무관하게 모든 구역을 방문한 선박 조회  
3. **통과 통계**: 구역별 통과 선박 수, 체류 시간, 이동 거리 등 통계
4. **Unix Timestamp 활용**: LineStringM의 M값 직접 사용으로 성능 최적화

### 현재 제한사항
- **구역 개수 고정**: 순차 통과 API는 정확히 3개 구역만 처리 가능
- **하드코딩된 JOIN**: v1, v2, v3 테이블 별칭으로 3중 JOIN 고정
- **동적 처리 불가**: 2개 또는 4개 이상 구역 조회 시 별도 개발 필요

## API 동작 흐름

### 1. 순차 통과 검출 흐름
```
Client → POST /api/v1/passages/sequential
         ↓
SequentialPassageController.getSequentialPassages()
  - 파라미터: @RequestBody SequentialPassageRequest
  - 유효성 검증: 시간 범위, 구역 ID 개수(정확히 3개)
         ↓
타입 분기 (GRID/AREA)
         ↓
SequentialAreaTrackingService.findSequentialGridPassages() 또는
SequentialAreaTrackingService.findSequentialAreaPassages()
  - WITH 절로 구역별 진입/진출 시간 집계
  - FIRST_VALUE/LAST_VALUE 윈도우 함수 사용
  - 3중 JOIN으로 순차 통과 검증
         ↓
buildVesselPassage()
  - 선박별 통과 정보 구성
  - t_vessel_latest_position 조인으로 선박 정보 추가
         ↓
Client ← SequentialPassageResponse
```

### 2. 주요 클래스 및 메소드

| 클래스 | 메소드 | 역할 | 파일 경로 |
|--------|--------|------|----------|
| **SequentialPassageController** | getSequentialPassages() | 순차 통과 API 엔트리포인트 | controller/SequentialPassageController.java |
| | getPassageStatistics() | 구역 통과 통계 조회 | |
| | getVesselsInAllZones() | 전체 구역 통과 선박 조회 | |
| | buildVesselPassage() | 응답 DTO 구성 | |
| | getVesselInfo() | 선박 정보 조회 (t_vessel_latest_position) | |
| **SequentialAreaTrackingService** | findSequentialGridPassages() | 해구 순차 통과 조회 | service/optimization/SequentialAreaTrackingService.java |
| | findSequentialAreaPassages() | 사용자정의구역 순차 통과 조회 | |
| | getAreaPassageStatistics() | 구역 통계 조회 | |
| **SequentialPassageRequest** | - | 요청 DTO | dto/SequentialPassageRequest.java |
| **SequentialPassageResponse** | - | 응답 DTO | dto/SequentialPassageResponse.java |

### 3. 데이터 처리 상세

#### 순차 통과 SQL 쿼리 구조
```sql
WITH vessel_passages AS (
    SELECT DISTINCT
        sig_src_cd,
        target_id,
        haegu_no,
        FIRST_VALUE(time_bucket) OVER (...) as entry_time,
        LAST_VALUE(time_bucket) OVER (...) as exit_time
    FROM signal.t_grid_vessel_tracks
    WHERE time_bucket BETWEEN ? AND ?
    AND haegu_no = ANY(ARRAY[?]::integer[])
)
SELECT ...
FROM vessel_passages v1
JOIN vessel_passages v2 ON ... AND v2.entry_time > v1.exit_time
JOIN vessel_passages v3 ON ... AND v3.entry_time > v2.exit_time
WHERE v1.haegu_no = ?
```

#### 테이블 사용
- **t_grid_vessel_tracks**: 해구별 항적 (파티션 테이블)
- **t_area_vessel_tracks**: 사용자정의구역별 항적 (파티션 테이블)
- **t_vessel_latest_position**: 선박 정보 조회

#### M값 활용
- Unix timestamp가 저장된 LineStringM의 M값을 직접 사용
- 시간 변환 없이 entry_time/exit_time 비교로 순차 통과 검증

### 4. API 엔드포인트

#### POST /api/v1/passages/sequential
순차 구역 통과 선박 조회

**Request Body:**
```json
{
  "startTime": "2025-08-01T00:00:00",
  "endTime": "2025-08-07T23:59:59",
  "type": "GRID",
  "zoneIds": ["93", "92", "100"],
  "sequentialOnly": true
}
```

**Response:**
```json
{
  "totalVessels": 24,
  "startTime": "2025-08-01T00:00:00",
  "endTime": "2025-08-07T23:59:59",
  "zones": ["93", "92", "100"],
  "passages": [
    {
      "sigSrcCd": "000001",
      "targetId": "440308230",
      "vesselInfo": {
        "shipName": "VESSEL NAME",
        "shipType": "30"
      },
      "zonePassages": [
        {
          "zoneId": "93",
          "zoneName": "해구 93",
          "entryTime": "2025-08-01T10:30:00",
          "exitTime": "2025-08-01T14:45:00"
        }
      ]
    }
  ],
  "processingTimeMs": 788
}
```

#### GET /api/v1/passages/statistics
구역 통과 통계 조회

**Parameters:**
- type: GRID 또는 AREA
- zoneId: 구역 ID
- startTime: 시작 시간
- endTime: 종료 시간

#### POST /api/v1/passages/all-zones
모든 구역 통과 선박 (순서 무관)

### 5. 성능 최적화

#### 인덱스 활용
```sql
-- 순차 통과 최적화 인덱스
CREATE INDEX idx_grid_vessel_haegu_time 
ON signal.t_grid_vessel_tracks (sig_src_cd, target_id, haegu_no, time_bucket);

CREATE INDEX idx_area_vessel_area_time
ON signal.t_area_vessel_tracks (sig_src_cd, target_id, area_id, time_bucket);
```

#### 최적화 전략
- **파티션 병렬 스캔**: 날짜별 파티션 동시 조회
- **윈도우 함수**: GROUP BY 대신 FIRST_VALUE/LAST_VALUE 사용
- **메모리 처리**: work_mem 256MB 설정으로 외부 정렬 방지
- **DO NOTHING**: INSERT 시 불필요한 UPDATE 제거

### 6. 성능 측정 결과

| 구분 | 최적화 전 | 최적화 후 | 개선율 |
|------|-----------|-----------|--------|
| 쿼리 실행 시간 | 596ms | 788ms | - |
| 외부 정렬 | 21MB (Disk) | 8MB (Memory) | 62% ↓ |
| INSERT 성능 | DO UPDATE | DO NOTHING | 30% ↑ |

### 7. 관련 파일 구조
```
signal_batch/
├── src/main/java/gc/mda/signal_batch/
│   ├── controller/
│   │   └── SequentialPassageController.java    # API 컨트롤러
│   ├── service/
│   │   └── optimization/
│   │       └── SequentialAreaTrackingService.java  # 핵심 서비스
│   ├── dto/
│   │   ├── SequentialPassageRequest.java      # 요청 DTO
│   │   └── SequentialPassageResponse.java     # 응답 DTO
│   └── util/
│       └── TrackClippingUtils.java            # Unix timestamp 적용 완료
```

### 8. 주의사항

#### PostgreSQL 버전
- WITH MATERIALIZED는 PostgreSQL 12+ 필요
- 하위 버전에서는 WITH만 사용

#### 배열 파라미터
- Integer 배열: `ARRAY[?]::integer[]`
- String 배열: `ARRAY[?]::varchar[]`

#### Null 처리
- t_vessel_latest_position 조인 시 EmptyResultDataAccessException 처리
- 선박 정보 없을 경우 null 반환

### 9. 향후 개선 계획

1. **동적 구역 처리 (우선순위: 높음)**
   - 2개~10개 구역 동적 처리 지원
   - 쿼리 빌더를 통한 동적 JOIN 생성
   - 재귀 CTE 활용 검토

2. **캐싱 전략**
   - 선박 정보 캐싱 (TTL 1시간)
   - 자주 조회되는 구역 조합 결과 캐싱

3. **배치 처리**
   - 대량 구역 조회 시 비동기 처리
   - 결과 페이징 지원

4. **실시간 알림**
   - 특정 구역 순차 통과 시 WebSocket 알림
   - 이상 패턴 감지 시 알림

---

## 문서 이력
- 2025-08-12: 초기 작성, Unix timestamp 기반 최적화 적용
