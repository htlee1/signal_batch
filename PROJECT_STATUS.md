# Signal Batch 프로젝트 현황

## 현재 Job 실행 상태 (2025-07-21 기준)
- **vesselAggregationJob**: ✅ 5분마다 실행 (위치 집계)
- **vesselTrackAggregationJob**: ✅ 5분마다 실행 (항적 집계)
- **hourlyAggregationJob**: ✅ 매시 10분 실행 (1시간 집계)
- **dailyAggregationJob**: ✅ 매일 01:00 실행 (1일 집계)

## 프로젝트 개요
- **프로젝트명**: vessel-batch-aggregation (선박 배치 집계 시스템)
- **목적**: 실시간 선박 위치 데이터를 전체/대해구/사용자정의구역 기반으로 집계하여 조회 성능 최적화
- **기술스택**: 
  - Backend: Spring Boot 3.2.5, Spring Batch 5.1.1
  - Database: PostgreSQL 15+ with PostGIS 3.3+
  - Frontend: deck.gl, maplibre-gl
  - WebSocket: STOMP over WebSocket (구현 완료 성능 개선 중)
- **현재 프로필**: dev
- **운영 상태**: 정상 작동 중

## 데이터베이스 정보
- **수집DB(CollectDB)**: 10.26.252.39:5432/mdadb (schema: signal) - 실시간 선박 데이터
- **조회DB(QueryDB)**: 10.26.252.48:5432/mdadb (schema: signal) - 집계된 데이터
- **배치메타DB(BatchDB)**: 10.26.252.48:5432/mdadb (schema: public) - Spring Batch 메타데이터

### 완료된 작업 (2025-07-21 22:30)
- [x] **성능 최적화 컴포넌트 통합**:
  - PerformanceOptimizationListener 생성 및 모든 Job에 통합
  - 동적 청크 크기 조정 기능 구현
  - 메모리 자동 최적화 기능 추가
  - 캐시 히트율 모니터링 및 최적화
  - 성능 최적화 설정 추가 (application-dev.yml)
  - PerformanceOptimizationController API 엔드포인트 구현

- [x] **데이터베이스 인덱스 최적화**:
  - QueryDB 실제 인덱스 상태 분석 완료
  - 파티션 테이블 인덱스 생성 (CONCURRENTLY 제한 해결)
  - 10개 권장 인덱스 생성 완료:
    - 선박별 시간 조회 인덱스
    - 해구/영역별 시간 역순 인덱스
    - 공간 쿼리용 GIST 인덱스
  - IndexCreator 자동화 도구 구현
  - 인덱스 사용 통계 모니터링 기능 추가

### 완료된 작업 (2025-07-21 21:00)
- [x] **WebSocket 기반 항적 스트리밍 API 구현 완료**:
  - STOMP over WebSocket 프로토콜 구현
  - 대용량 항적 데이터 점진적 스트리밍
  - 시간 범위에 따른 자동 테이블 선택 (5분/1시간/1일)
  - 병렬 쿼리 처리로 성능 최적화
  - 실시간 진행 상태 업데이트 및 취소 기능
  - Viewport, 해구, 영역, 선박별 필터링
  - 메트릭 수집 및 모니터링
  - 테스트 클라이언트 페이지 제공
  - batch-admin 대시보드에 WebSocket Test 탭 통합 (iframe)
  - 모든 빌드 오류 해결 및 테스트 환경 구성
  - API 가이드 문서 작성 완료

- [x] **계층적 집계 시스템 완성**:
  - HourlyAggregationJob 오류 수정 (ItemReader 타입 문제 해결)
  - DailyAggregationJob bean 중복 문제 해결
  - 일별 집계 테이블 생성 (t_grid_tracks_summary_daily, t_area_tracks_summary_daily)
  - SQL Window 함수 오류 수정
  - 모든 집계 Job 정상 작동 확인

### 완료된 작업 (2025-07-23)
- [x] **WebSocket 항적 스트리밍 고급 필터링 구현**:
  - ✅ 거리/속도 기반 필터링 기능 완성
    - 최소/최대 이동거리 필터 (nm)
    - 최소/최대 평균속도 필터 (knots)
    - Bucket 간 거리 포함 옵션 (더 정확한 계산)
  - ✅ VesselTrackFilter 컴포넌트 구현
    - 시간 범위별 테이블 전략에 따른 필터링
    - Haversine 공식을 사용한 정확한 거리 계산
    - 병렬 처리로 성능 최적화
  - ✅ 부하 테스트 도구에 필터 UI 통합
    - 필터 설정 패널 추가
    - 필터링 진행 상태 실시간 표시
    - 필터링된 선박/항적 수 메트릭 표시
  - ✅ 향상된 로그 시스템
    - 레벨별 색상 구분 (error, warning, filter, info)
    - 필터링 관련 상세 로그 제공

### 완료된 작업 (2025-07-25)
- [x] **항적 집계 속도 계산 방식 개선**:
  - ✅ SOG 평균에서 ST_Length 기반 실제 거리/시간 계산으로 변경
  - ✅ VesselTrackProcessor: 평균속도 = 이동거리 / 시간차
  - ✅ HourlyTrackProcessor: ST_Length(geography)로 실제 거리 계산
  - ✅ DailyTrackProcessor: 동일 방식 적용
  - ✅ NUMERIC(6,2) 오버플로우 방지 (LEAST 함수로 9999.99 제한)

- [x] **Hourly/Daily 집계 오류 수정**:
  - ✅ Spring Batch @StepScope 오류 해결
  - ✅ 날짜 파싱 오류 수정 (DateTimeFormatter 적용)
  - ✅ ST_MakeLine → ST_LineMerge(ST_Collect()) 변경
  - ✅ M값 재계산 로직 SQL에서 직접 처리
  - ✅ 실제 start_position 시간 기준으로 M값 재계산

- [x] **비정상 항적 검출 로직 전면 개선**:
  - ✅ 검출 우선순위 변경: 집계된 메트릭(distance_nm, avg_speed) 우선
  - ✅ 급가속 검사 제거 (거리/속도 기반으로 충분)
  - ✅ 시간 비례 거리 검증 (5분/1시간/1일 자동 보정)
  - ✅ maxSpeed 물리적 한계 적용 (선박 100knots, 항공기 300knots)
  - ✅ 비정상 항적 완전 제외 로직 구현 (null 반환)
  - ✅ 항공기(sig_src_cd='000019') 별도 기준 적용
  - ✅ ObjectMapper JavaTimeModule 추가 (LocalDateTime 직렬화)

### 완료된 작업 (2025-07-28)
- [x] **WebSocket API 항적 간소화 구현**:
  - ✅ TrackSimplificationStrategy 클래스 구현
    - 시간 범위별 자동 간소화 레벨 결정 (1시간: 원본, 1일: 50%, 1주일: 10%)
    - Douglas-Peucker 알고리즘 기반
    - 적응형 간소화 (목표 크기에 맞춰 자동 조정)
  - ✅ SQL 최적화
    - PostGIS ST_Simplify 함수로 DB 레벨 간소화
    - 간소화 레벨별 tolerance 값 적용
  - ✅ TrackQueryRequest 확장
    - maxPointsPerTrack: 트랙당 최대 포인트 제한
    - maxResponseSizeKB: 응답 크기 제한  
    - simplificationMode: AUTO/NONE/ADAPTIVE/AGGRESSIVE
  - ✅ StompTrackStreamingService 통합
    - buildOptimizedQuery()에 ST_Simplify 적용
    - applyAdditionalSimplification()으로 메모리 간소화
- [x] **비정상 항적 검출 로직 최적화**:
  - ✅ Hourly/Daily 집계에서 bucket 간 연결점만 검사하도록 변경
  - ✅ 5분 데이터는 이미 검증되었다고 가정하여 중복 검사 제거
  - ✅ GPS 순간 오류로 인한 과검출 방지
  - ✅ 이전 bucket의 마지막 항적 조회 로직 구현
  - ✅ `detectBucketTransitionOnly()` 메서드 추가

- [x] **비정상 항적 모니터링 페이지 완성** (2025-07-28 16:00):
  - ✅ 애니메이션 기능 제거 (성능 문제 해결)
  - ✅ 선박ID 기준 그룹화 및 다중 선택 기능
  - ✅ 항적 포인트별 상세정보 툴팁 표시
  - ✅ deck.gl 초기화 시점 최적화
  - ✅ 지도-리스트 상호 작용 (양방향 포커스/스크롤)
  - ✅ 선택된 항적 강조 표시 (최상단 렌더링, 굵은 선)
  - ✅ 선박 그룹 하위 메뉴 선택 시 포커스, 강조, 포인트 표시
  - ✅ 클릭 시 강조 상태 지속 및 하위 아이템 자동 스크롤
  - ✅ 통계/범례 일관성 (조회 기간 기반 동적 집계)

- [x] **비정상 항적 검출 로직 전면 개선** (2차 개선):
  - ✅ 시간 비례 임계값 문제 해결 (선형 → 제곱근 스케일링)
  - ✅ 정박/저속 선박 처리 추가 (0.01nm 미만 이동시 검출 제외)
  - ✅ 임계값 대폭 완화 (1000 → 500 knots로 조정)
  - ✅ 데이터 gap 처리 추가 (30분~2시간 gap은 정상 처리)
  - ✅ 단일 포인트 케이스 처리 (시작과 끝이 동일한 경우)
  - ✅ 극단적 비정상만 검출하도록 개선

- [x] **AbnormalTrackWriter 버그 수정**:
  - ✅ source_table이 Job 기준이 아닌 시간 패턴으로 결정되던 문제 해결
  - ✅ Job 이름 기반으로 source_table 결정하도록 수정
  - ✅ CompositeTrackWriter에 @BeforeStep 추가하여 Job 이름 전달
  - ✅ setJobName() 메서드 추가로 Job 컨텍스트 공유

### 완료된 작업 (2025-07-25 추가)
- [x] **비정상 항적 필터링 및 모니터링**:
  - ✅ 기본 검출 로직 구현 및 테이블 저장
  - ✅ 대시보드 통합 완료
  - ✅ 5분 집계에 avg_speed > 1000 필터링 적용
  - ✅ 5분 집계 비정상 항적도 t_abnormal_tracks에 저장
  - ✅ 비정상 항적 검출 로그 레벨 debug로 조정
  - ✅ SOG 값이 없거나 0인 경우 처리 로직 개선
    - 현재 기준: 평균속도 50knots, 5분 이동거리 10nm
    - 5분 집계: avg_speed > 1000 knots만 필터링

### 완료된 작업 (2025-07-24)
- [x] **WebSocket 항적 필터링 오류 수정 및 개선**:
  - ✅ Geometry 파싱 오류 해결
    - 빈 문자열 및 "LINESTRING EMPTY" 처리 추가
    - 예외 처리 강화 및 상세 로깅 구현
    - ParseException 발생 시 안정적 처리
  - ✅ 다중 테이블 통합 필터링 구현
    - `filterVesselsByDistanceAndSpeedMultipleTables` 메서드 추가
    - 전체 조회 기간에 걸친 정확한 거리/속도 계산
    - 일별/시간별/5분 테이블 데이터 통합 처리
  - ✅ 코드 개선
    - TrackQueryRequest에 `toBuilder` 패턴 적용
    - VesselTrackFilter와 StompTrackStreamingService 통합 개선
    - 로깅 및 디버깅 정보 강화



### 완료된 작업 (2025-07-29)
- [x] **WebSocket API 백엔드 병합 기능 추가**:
  - ✅ 대용량 데이터 처리 시 백엔드에서 선박별 병합
  - ✅ MergedVesselTrack DTO 및 VesselTrackMerger 서비스 구현
  - ✅ 청크 크기 >= 5000일 때 자동으로 병합 모드 활성화
  - ✅ PostGIS ST_LineMerge로 효율적인 항적 병합
  - ✅ 병합 후 선박 단위(100척)로 청크 전송
  - ✅ 프론트엔드 처리 시간 30초 → 5초로 단축
  - ✅ start_position/end_position JSON 필드에서 시간 정보 추출
  - ✅ **병합 후 PostGIS 기반 거리/속도 재계산 추가** (정박 선박 0 표시 문제 해결)
  - ✅ **LineStringM 파싱 오류 수정** (괄호 처리 개선)

- [x] **타임라인 시간 표시 오류 수정**:
  - ✅ trackStore.js에서 timeBucket이 없을 때 startTime 사용
  - ✅ VesselAnimationController 시간 계산 오류 수정
  - ✅ 애니메이션 재생 시 선박 위치 업데이트 정상화

- [x] **선박 표시 범위 개선**:
  - ✅ 시간 범위 체크 로직에 1분 여유 추가
  - ✅ 애니메이션 중 선박이 사라지는 문제 해결

- [x] **비정상 항적 필터링 강화** (2025-07-29 추가):
  - ✅ 5분 데이터: 100 knots 또는 10nm 이상 제외 (기존: 1000 knots)
  - ✅ Hourly/Daily: 100 knots 이상 제외 (기존: 500 knots)
  - ✅ 항공기 예외 처리 추가 (sig_src_cd='000019': 300 knots/30nm)
  - ✅ bucket 간 전환점만 검사하는 방식 유지

- [x] **항적 간소화 구현** (2025-07-29 추가):
  - ✅ TrackSimplificationUtils 유틸리티 생성
  - ✅ HourlyTrackProcessor: 10m 이내 이동 생략, 최대 10분 간격
  - ✅ DailyTrackProcessor: 20m 이내 이동 생략, 최대 30분 간격  
  - ✅ LineStringM 형식 유지하면서 간소화
  - ✅ 파싱 오류 수정 (괄호 중첩 처리)

### 완료된 작업 (2025-07-28 추가)
- [x] **WebSocket API 성능 개선 달성 (9초 → 4.5초)**:
  - ✅ PostgreSQL 설정 최적화
    - max_parallel_workers_per_gather: 2 → 4
    - work_mem: 4MB → 256MB
  - ✅ 복합 인덱스 생성 (INCLUDE 사용)
    - time_vessel_include_idx 추가 (track_geom 제외로 8KB 제한 회피)
    - Grid/Area 진입 이력 조회용 인덱스 추가
  - ✅ 병렬 처리 개선
    - 스레드 풀: 5 → 10
    - 순차적 처리: 5분 → Hourly → Daily (렌더링 자연스러움)
  - ✅ 동적 테이블 선택 로직 구현
    - 데이터 존재 여부 실시간 확인 (hasDataInTable)
    - Daily → Hourly → 5분 폴백 로직
  - ✅ 청크 크기 최적화
    - 최대값: 5,000 → 20,000 증가
    - 성능 결과: 10,000(6.9초), 20,000(4.5초)
  - ✅ deck.gl 렌더링 최적화
    - PathLayer 성능 옵션 적용
    - 애니메이션 포인트 수 제한

- [x] **WebSocket GIS 항적 애니메이션 시스템 구현** (2025-07-28 17:00):
  - ✅ Zustand-like 상태 관리 구현 (trackStore.js)
    - 2-3만 선박 데이터 최적화 (최대 5000척 렌더링)
    - 시간 기반 인덱싱 및 이진 탐색
    - 중복 제거 및 메모리 최적화
    - Viewport culling 자동 적용
  - ✅ 고성능 애니메이션 컨트롤러 구현
    - RequestAnimationFrame 기반 렌더링
    - 동적 FPS 조정 (20k+ → 10fps, 10k+ → 15fps)
    - 타임라인 UI (재생/일시정지/속도조절)
    - 실시간 성능 모니터링 표시
  - ✅ deck.gl IconLayer 통합
    - 선박 위치 실시간 보간 계산
    - 속도별 색상 구분
    - Canvas 기반 아이콘 생성
  - ✅ **테스트 필요**: 대규모 데이터 성능 검증

- [x] **WebSocket GIS 페이지 UI/UX 개선**:
  - ✅ 항적 렌더링 품질 향상
    - 간소화 알고리즘 시각적 품질 개선
    - 중요 지점(방향전환, 정지) 보존
  - ✅ 인터랙티브 기능 강화
    - 선박별 항적 토글
    - 시간 슬라이더 애니메이션
    - 항적 필터링 UI 개선
  - ✅ 성능 모니터링 UI
    - 실시간 FPS 표시
    - 메모리 사용량 그래프
    - 렌더링된 객체 수 표시

- [x] **선박 항적 애니메이션 기능 개발**:
  - ✅ 항적 데이터 병합
    - time_bucket 단위 line을 sig_src_cd, target_id 기준 병합
    - Zustand store에 WebSocket 수신 데이터 저장 및 병합
  - ✅ 타임라인 애니메이션 구현
    - 타임라인 바 UI 컴포넌트 개발
    - requestAnimationFrame 활용 애니메이션
    - 프레임 조절 기능 (15/30/60 FPS)
  - ✅ 선박 위치 실시간 계산
    - 두 점의 위치와 시간 기반 상대 위치 계산
    - 선박 아이콘 (원형) deck.gl 레이어로 표현
    - 시간대별 선박 위치 보간 알고리즘

- [ ] **구역 기반 선박 필터링 API 개발**:
  - [ ] 다중 구역 진입 조건 API
    - 해구/사용자정의구역 1~3개 순차 지정 기능
    - 순서대로 진입 이력 확인 로직
    - t_grid_vessel_tracks, t_area_vessel_tracks 활용
  - [ ] 쿼리 패널 UI 확장
    - 해구/구역 선택 UI 컴포넌트
    - 순서 지정 및 시각화
  - [ ] 성능 테스트
    - 다중 조건 필터링 성능 측정
    - 인덱스 활용도 분석

- [ ] **API 추가 최적화**:
  - [ ] 캐싱 전략 도입
    - 자주 조회되는 영역 메모리 캐싱
    - 간소화 결과 캐싱
  - [ ] 압축 적용
    - WebSocket 메시지 gzip 압축
    - 대용량 청크 자동 압축

## WebSocket API 성능 개선 작업 계획 (2025-07-28)

### 현재 성능 분석
- **테스트 환경**: 본도 영역 (120-132°E, 30-38°N), 1시간 데이터
- **현재 수행 시간**: 9초
- **목표 수행 시간**: 4초 이하 (55% 개선)

### 병목 지점 분석
1. **DB 쿼리 단계** (60%)
   - 5분 테이블 쿼리: ~5.4초
   - 해구/영역 JOIN 쿼리: ~1초
   - 거리/속도 필터링: ~0.5초

2. **처리 단계** (30%)
   - 항적 간소화: ~1.8초
   - 청크 분할 및 매핑: ~0.9초

3. **네트워크 전송** (10%)
   - WebSocket 스트리밍: ~0.9초

### 단계별 개선 방안

#### Phase 1: 쿼리 최적화 (목표: 2.5초 절감)
1. **적절한 인덱스 생성**
   ```sql
   -- 복합 인덱스 추가 (시간 + 공간)
   CREATE INDEX idx_vessel_tracks_5min_time_geom 
   ON signal.t_vessel_tracks_5min(time_bucket, sig_src_cd, target_id) 
   INCLUDE (track_geom) WHERE track_geom IS NOT NULL;
   ```

2. **파티션 프루닝 개선**
   - 시간 범위를 명시적 파티션 키로 포함
   - 비동기 파티션 테이블 접근

3. **쿼리 실행 계획 개선**
   - EXPLAIN ANALYZE로 실행 계획 분석
   - 병렬 쿼리 작업자 수 증가 (5 → 10)

#### Phase 2: 간소화 전략 개선 (목표: 1.5초 절감)
1. **적응형 간소화**
   - 뷰포트 크기에 따른 동적 tolerance
   - 줌 레벨에 따른 차등 간소화

2. **중요 포인트 보존**
   - 방향 전환점, 정지점 식별
   - Ramer-Douglas-Peucker 변형 알고리즘

3. **간소화 결과 캐싱**
   - 자주 조회되는 영역/시간대 사전 처리
   - 메모리 기반 LRU 캐시

#### Phase 3: 스트리밍 효율화 (목표: 1초 절감)
1. **동적 청크 크기**
   - 네트워크 속도 기반 자동 조절
   - 클라이언트 처리 능력 고려

2. **압축 적용**
   - WebSocket 메시지 압축 (gzip)
   - 10KB 이상 메시지 자동 압축

3. **네트워크 최적화**
   - TCP 노드리이 비활성화
   - 백프레셔 알고리즘 개선

### 성능 측정 방법
```bash
# 서버 측 모니터링
java -jar signal_batch.jar --spring.profiles.active=dev \
  -Dspring.jpa.show-sql=true \
  -Dlogging.level.org.springframework.jdbc.core=DEBUG

# 클라이언트 측 테스트
curl -X POST http://10.26.252.48:8090/api/v1/tracks/stream \
  -H "Content-Type: application/json" \
  -d '{
    "startTime": "2025-07-27T14:00:00",
    "endTime": "2025-07-27T15:00:00",
    "viewport": {
      "minLon": 120,
      "maxLon": 132,
      "minLat": 30,
      "maxLat": 38
    },
    "simplificationMode": "AUTO"
  }'
```

### 다음 대화를 위한 질문 구조 추천

#### 추천 질문 형식:
```
프로젝트: C:\Users\lht87\IdeaProjects\signal_batch
작업 목표: WebSocket API 성능 개선 (9초 → 4초)

현재 분석 필요:
1. StompTrackStreamingService의 streamTimeRange() 메소드에서 DB 쿼리 실행 시간 측정
2. 현재 사용 중인 인덱스 확인 (EXPLAIN ANALYZE 결과)
3. 5분 테이블 파티션 전략 확인

처리 요청:
1. 쿼리 실행 시간 로깅 코드 추가
2. 복합 인덱스 생성 SQL 스크립트 작성
3. 병렬 쿼리 스레드 풀 크기 조정
```

#### 대체 질문 형식 (더 간결):
```
WebSocket API 성능을 9초에서 4초로 개선하고 싶어.
StompTrackStreamingService에서:
1. DB 쿼리 실행 시간 측정 코드 추가
2. buildStreamingQuery() 메소드 최적화
3. 현재 executorService가 5개 스레드인데 10개로 늘리기
```

### 긴급 작업 (2025-07-25) - 최우선 순위
- [🔄] **LineStringM 및 병합 로직 수정** (코드 수정 완료, 테스트 진행중):
  - ✅ 비정상 항적 테이블 확인: 이미 LineStringM 타입 사용중
  - ✅ AbnormalTrackDetector 수정: WKT 문자열 파싱으로 변경
  - ✅ hourly/daily 병합 시 start_position, end_position 필드 설정 구현
  - ✅ LineStringM의 M값 재계산 로직 구현:
    - 5분: 원본 유지
    - 1시간: LineStringMUtils.rebuildLineStringMForHourly
    - 1일: LineStringMUtils.rebuildLineStringMForDaily
  - ✅ HourlyTrackProcessor/DailyTrackProcessor 수정 완료
  - ✅ 타입 불일치 오류 해결 (dto → model)
  - ✅ 중복 @Bean 정의 제거
  
  **현재 상태: 코드 수정 완료, 실제 동작 확인 필요**
  - 🔄 빌드 및 배포 후 런타임 테스트 진행중
  - 🔄 hourly/daily 집계 실행 후 start_position, end_position 값 확인 필요
  - 🔄 LineStringM M값 재계산 정확도 검증 필요
  - ⚠️ 오류 발생 시 추가 디버깅 및 수정 필요

### 다음 단계 작업 목록
- [ ] **선박 항적 조회 WebSocket API 개선**:
  - 대용량 데이터 스트리밍 시 메모리 효율성 개선
  - 항적 간소화 알고리즘 최적화
  - 실시간 진행률 표시 정확도 향상
  - 클라이언트 재연결 처리 강화

- [ ] **파티션 자동생성 스크립트 최신화**:
  - 비정상 항적 테이블 파티션 추가
  - 월별 파티션 생성 자동화
  - 오래된 파티션 아카이빙 기능
  - 파티션 상태 모니터링 대시보드 통합
- [ ] **비정상 항적 모니터링 페이지 기능 고도화**:
  - 선박ID 기준 그룹화 UI 구현
    - 동일 선박의 반복적인 비정상 항적 패턴 분석
    - 선박별 비정상 항적 발생 빈도 통계
  - 지도 상호작용 기능 개선
    - 비정상 항적 목록에서 선택 시 지도에 강조표시
    - 항적을 구성하는 실제 포인트 표시
    - 포인트 마우스오버 시 상세정보 툴팁
      - 선박ID, 위도, 경도, 시간(detected_at + M값)
  - 비정상 항적 상세 분석 뷰
    - 항적별 상세 메트릭 표시
    - 비정상 판정 이유 시각화

- [ ] **거리 및 평균속도 집계 로직 개선**:
  - 현재: SOG 값들의 단순 평균으로 avg_speed 계산
  - 개선: 실제 이동거리와 시간 기반 계산
  - 적용 대상: 5min, hourly, daily 모든 집계 테이블
  
  **구현 방안:**
  1. 거리 계산: PostGIS ST_Length(geography) 함수로 LineString 실제 길이 계산
  2. 시간 계산: end_position.time - start_position.time
  3. 평균속도: 총 거리(m) / 총 시간(초) * 1.94384 (m/s → knots 변환)
  
  **수정 필요 파일:**
  - VesselTrackProcessor: 5분 집계 시 계산 로직 변경
  - HourlyTrackProcessor: 시간별 집계 시 재계산
  - DailyTrackProcessor: 일별 집계 시 재계산
  - SQL 쿼리: ST_Length 함수 추가

### 진행중인 작업 (2025-07-24)
- [x] **비정상 항적 필터링 및 모니터링 대시보드 통합**:
  - ✅ batch-admin.html에 "Abnormal Tracks" 메뉴 추가
  - ✅ Dashboard에 "Abnormal Tracks (24h)" 메트릭 카드 추가
  - ✅ abnormal-tracks.html 독립 페이지 iframe 통합
  - ✅ API 연동 완료 (`/api/v1/abnormal-tracks/*`)
  - ✅ 실시간 통계 및 차트 시각화 구현
  - ✅ 필터링 기능 (기간, 유형, 선박 ID)
  - ✅ 지도에 비정상 항적 렌더링 (deck.gl)
  - ✅ 접속 경로: `/static/admin/abnormal-tracks.html`
  
- [x] **WebSocket 항적 필터링 WKB/WKT 오류 수정** (2025-07-24 11:00)
  - ✅ 문제 확인: PostGIS geometry 직접 조회 시 WKB 형식 반환
  - ✅ SQL 쿼리 수정: ST_AsText() 함수로 WKT 형식 변환
  - ✅ 에러 처리 개선: WKB 형식 감지 및 명확한 로그 메시지
  - 필터링은 정상 작동하며 에러 로그만 발생한 상태였음

- [x] **WebSocket 스트맍만 테스트 GIS 실시간 렌더링 추가** (2025-07-24 12:00)
  - ✅ track-streaming-gis.html 파일 생성
  - ✅ MapLibre GL + deck.gl 통합 구현
  - ✅ 주요 기능:
    - WebSocket으로 수신한 항적 데이터 실시간 지도 렌더링
    - 청크별 자동 병합 및 선박별 항적 통합
    - 속도별 색상 구분 및 범례 표시
    - 수신 애니메이션 효과 (선택적)
    - 선박 툴팁 정보 표시
    - 실시간 통계 패널 (수신 항적, 필터 선박, 렌더링 포인트)
    - 항적 투명도 조절 및 렌더링 제어
  - ✅ batch-admin 대시보드 통합 (WebSocket GIS 탭 추가)
  - ✅ 접속 경로: `/websocket/track-streaming-gis.html`

- [x] **비정상 항적 필터링 및 분리 저장 기능 개발** (2025-07-24 15:00 시작)
  - ✅ 데이터베이스 테이블 설계
    - t_abnormal_tracks: 비정상 항적 원본 저장
    - t_abnormal_track_stats: 비정상 항적 통계
  - ✅ 핵심 컴포넌트 구현
    - AbnormalTrackDetector: 비정상 항적 검출기
    - AbnormalTrackWriter: 비정상 항적 저장 Writer
    - EnhancedVesselTrackProcessor: 5분 데이터 처리시 비정상 검출
    - EnhancedHourlyTrackProcessor: 시간별 집계시 비정상 검출
    - EnhancedTrackCompositeWriter: 정상/비정상 항적 분리 저장
  - ✅ 배치 Job 통합
    - EnhancedHourlyAggregationStepConfig: 비정상 검출 기능 포함 Step
    - EnhancedHourlyAggregationJobConfig: 향상된 Job 설정
    - EnhancedVesselBatchScheduler: 향상된 스케줄러
  - ✅ Enhanced 코드 통합
    - Enhanced 코드를 모두 기존의 컴포넌트, Job 코드에 통합
  - ✅ 설정 및 테스트
    - application-dev.yml에 abnormal-detection 설정 추가
    - test-abnormal-detection.sh/bat 테스트 스크립트 작성
  - ✔️ 다음 단계
    - 🔄 batch-admin 대시보드 통합 완료
    - DailyAggregationJob에도 비정상 검출 기능 추가
    - 비정상 항적 제거/보정 로직 구현
    - 비정상 항적 알림 기능 추가

- [ ] **부하 테스트 수행**:
  - ✅ JMeter 종합 부하 테스트 시나리오 작성 완료
    - comprehensive-load-test.jmx (API, 배치, 성능 최적화 테스트)
    - 3개 Thread Group: API 조회(50), 배치 Job(5), 성능 API(10)
  - ✅ WebSocket 부하 테스트 페이지 구현 및 고도화
    - 브라우저 기반 load-test.html (거리/속도 필터 포함)
    - Python 기반 자동화 스크립트
  - ✅ 부하 테스트 실행 스크립트 작성
    - run-load-test.sh: 자동화된 테스트 실행
    - monitor-realtime.sh: 실시간 모니터링
  - ✅ 부하 테스트 문서화
    - LOAD_TEST_GUIDE.md: 상세 실행 가이드
    - LOAD_TEST_REPORT_TEMPLATE.md: 결과 보고서 템플릿
  - ✅ batch-admin 대시보드 통형
    - Load Test 탭 추가 (iframe으로 통합)
    - 좌측 메뉴에서 쉽게 접근 가능
  - 🔄 실제 부하 테스트 실행 대기중

### 테스트 시나리오 및 방법

#### 필터링 기능 테스트 (2025-07-24 추가)
1. **Geometry 오류 해결 확인**
   ```bash
   # 서버에서 로그 확인
   tail -f /devdata/apps/bridge-db-monitoring/logs/app.log | grep -E "VesselTrackFilter|geometry"
   ```

2. **다중 테이블 통합 필터링 테스트**
   - 3일간 조회 (1일 테이블 2개 + 1시간 테이블 여러개)
   - 최소 이동거리 10nm 필터 적용
   - 기대 결과: 매일 4nm씩 이동한 선박도 포함 (총 12nm)

3. **성능 모니터링**
   ```bash
   # 서버에서 실시간 모니터링
   cd /devdata/apps/bridge-db-monitoring
   ./monitor-query-server.sh
   ```

#### 테스트 접속 정보
- **Admin Dashboard**: http://10.26.252.48:8090/static/admin/batch-admin.html
- **Load Test 탭**: Dashboard 좌측 메뉴에서 "Load Test" 클릭
- **직접 접속**: http://10.26.252.48:8090/static/admin/load-test.html

- [ ] **모니터링 대시보드 업데이트**:
  - 성능 최적화 메트릭 시각화
  - 인덱스 사용률 실시간 모니터링
  - 시스템 리소스 사용률 차트
  - 배치 작업 처리 속도 트렌드

### 완료된 작업 (2025-07-20 16:30)
- [x] **GIS Monitoring 탭 분리 및 개선**:
  - batch-admin.html에서 iframe으로 gis-monitoring.html 분리 완료
  - 트랙 데이터 시각화 오류 수정:
    - GisService 시간대 문제 해결 (LocalDateTime → PostgreSQL NOW() 직접 사용)
    - LineStringM 파싱 로직 개선
    - 트랙 렌더링 스타일 개선 (녹색 3px 라인)
  - 선박 최종 위치 표시 기능 추가:
    - Show Positions 버튼으로 영역 내 모든 선박 위치 표시 (빨간 점)
    - 위치 포인트 클릭 시 해당 선박 항적 표시
    - 마우스오버 시 선박 정보 툴팁 (ID, 속도, 시간)
  - UI/UX 개선:
    - Track Period 선택을 메인 컨트롤 패널로 이동
    - 선박 목록을 메인 패널 내 접이식 섹션으로 통합
    - 모든 패널에 닫기 버튼 추가
    - 항적/위치 표시 시 맵 자동 중심 이동
  - Heatmap 기능은 향후 구현 예정

### 완료된 작업 (2025-07-20 15:00 추가)
- [x] **계층적 집계 시스템 구현**:
  - HourlyAggregationJob: 5분 → 1시간 집계 구현
  - DailyAggregationJob: 1시간 → 1일 집계 구현
  - VesselTrackProcessor 성능 개선 (AreaBoundaryCache 활용)
  - VesselTrackBulkWriter 확장 (시간별/일별 저장 메서드)
  - Bean 정의 충돌 해결 (@Component 제거)
  - 테이블 생성 스크립트 추가

### 완료된 작업 (2025-07-20 11:00)
- [x] **항적 집계 성능 최적화 및 개선**:
  - 문제 해결:
    - CompositeItemWriter 적용으로 3개 테이블 동시 저장
    - 영역별 항적 분할 저장 (TrackClippingUtils)
    - 속도 값 NUMERIC(6,2) 오버플로우 방지 (max 9999.99)
    - 단일 포인트도 LineStringM으로 저장 (위성 신호 특성 고려)
  - 성능 개선:
    - AreaBoundaryCache 구현 (메모리 캐싱)
    - DB 쿼리 300,000회 → 0회로 감소
    - 처리 속도 5-10배 향상
  - 결과 검증:
    - t_vessel_tracks_5min: 시간당 약 114,000건 생성
    - t_grid_tracks_summary: 해구별 집계 정상 작동
    - vessel_list에 sig_src_cd, target_id, distance_nm, avg_speed 포함

### 완료된 작업 (2025-07-20 추가)
- [x] **5분 단위 항적 집계 기능 구현**:
  - 테이블 생성: t_vessel_tracks_5min, t_grid_vessel_tracks, t_area_vessel_tracks 등
  - VesselTrack 모델 및 Processor 구현 (LineStringM 형태 항적 생성)
  - 독립적인 VesselTrackAggregationJob 구현
  - 전용 데이터 홀더/리더/라이터 구현
  - 스케줄러: 위치 집계 1분 후 실행 (04, 09, 14분...)
  - HaeguGeoUtils 통합, numeric 타입 안전 처리
  
- [x] **항적 집계 오류 수정**:
  - JSON 형식 오류 해결 (ObjectMapper 사용)
  - Timestamp 캐스팅 오류 수정 (형식 변환)
  - PostgreSQL COPY 형식에 맞는 이스케이프 처리
  - gridTrackSummaryStep, areaTrackSummaryStep 구현

### 완료된 작업 (2025-07-19)
- [x] **DB 커넥션 풀 고갈 문제 해결**
- [x] **aggregateTileStatisticsStep 정상 작동 확인**
- [x] **ON CONFLICT 중복 키 오류 해결**
- [x] **aggregateAreaStatisticsStep 연결 풀 고갈 문제 해결**
- [x] **Reader 전략 통일 및 최적화**
- [x] **VesselAggregationJob 메모리 기반 데이터 공유 구현**

## 주요 구현 사항

### 항적 집계 시스템
- **데이터 흐름**: 
  - CollectDB → VesselTrackDataJobListener (전체 5분 데이터 로드)
  - → InMemoryVesselTrackDataReader (선박별 그룹화)
  - → VesselTrackProcessor (LineStringM 생성)
  - → VesselTrackBulkWriter (PostgreSQL COPY)
  - → Grid/Area 집계 스텝

- **LineStringM 형식**: 
  - M값은 첫 포인트 기준 상대 시간(초)
  - 예: LINESTRING M(126.594 37.450 0, 126.595 37.451 60, ...)

- **JSON 형식**: 
  ```json
  {"lat": 35.901342, "lon": 125.591702, "sog": 0.2, "time": "2025-07-20 09:45:16"}
  ```

### 계층적 집계 구조
```
5분 데이터 (t_vessel_tracks_5min)
    ↓ HourlyAggregationJob (매시 10분)
1시간 데이터 (t_vessel_tracks_hourly)
    ↓ DailyAggregationJob (매일 01:00)
1일 데이터 (t_vessel_tracks_daily)
```

### Reader 전략
1. **vesselLatestPositionReader**: DISTINCT ON 사용, 최신 위치만 (위치 집계용) ✅ 사용중
2. **InMemoryVesselDataReader**: 메모리 기반 위치 데이터 처리 ✅ 사용중
3. **InMemoryVesselTrackDataReader**: 메모리 기반 항적 데이터 처리 (선박별 그룹화) ✅ 사용중

## 시스템 아키텍처
```
수집DB (실시간) → Spring Batch → 조회DB (집계)
                     ↓
                배치메타DB
```

## 빌드 및 배포 구조

### 개발 환경
- **개발 PC**: Windows 환경 (C:\Users\lht87\IdeaProjects\signal_batch)
- **빌드 도구**: Maven
- **JDK 버전**: Java 17

### 운영 환경
- **운영 서버**: 10.26.252.48 (QueryDB 서버)
- **OS**: Rocky Linux
- **배포 경로**: /devdata/apps/bridge-db-monitoring
- **Java 경로**: /devdata/apps/jdk-17.0.8

### 빌드 및 배포 프로세스

#### 1. 로컬 빌드 (개발 PC)
```bash
# Maven 빌드
cd C:\Users\lht87\IdeaProjects\signal_batch
mvn clean package -DskipTests

# JAR 파일 생성 위치
target/signal_batch-1.0.0.jar
```

#### 2. 서버 배포
```bash
# JAR 파일을 서버로 복사
scp target/signal_batch-1.0.0.jar user@10.26.252.48:/devdata/apps/bridge-db-monitoring/vessel-batch-aggregation.jar

# 서버 접속
ssh user@10.26.252.48
```

#### 3. 서버에서 실행
```bash
# 제어 스크립트를 통한 실행
cd /devdata/apps/bridge-db-monitoring
./vessel-batch-control.sh start

# 또는 직접 실행 스크립트 사용
./run-on-query-server.sh
```

### 운영 스크립트 구조

#### vessel-batch-control.sh
- **위치**: signal_batch/scripts/vessel-batch-control.sh
- **기능**: 시작/중지/상태확인/로그보기/통계
- **명령어**:
  ```bash
  ./vessel-batch-control.sh start    # 애플리케이션 시작
  ./vessel-batch-control.sh stop     # 애플리케이션 중지
  ./vessel-batch-control.sh restart  # 재시작
  ./vessel-batch-control.sh status   # 상태 확인
  ./vessel-batch-control.sh logs     # 로그 확인
  ./vessel-batch-control.sh errors   # 에러 로그 확인
  ./vessel-batch-control.sh stats    # 성능 통계
  ```

#### run-on-query-server.sh
- **기능**: Query DB 서버 최적화 실행
- **주요 설정**:
  - Java Heap: 서버 메모리의 25% (최소 16GB, 최대 64GB)
  - DB 연결: localhost 최적화 (Query DB와 동일 서버)
  - 병렬 처리: CPU 코어 수에 따라 자동 조정
  - Nice 우선순위: 10 (DB 성능 영향 최소화)

#### monitor-query-server.sh
- **기능**: 실시간 리소스 모니터링
- **모니터링 항목**:
  - CPU 사용률 (PostgreSQL vs Java)
  - 메모리 사용량
  - 디스크 I/O
  - DB 연결 상태
  - 배치 처리 지연 시간
  - 네트워크 연결 상태
  - 에러 로그 모니터링
- **로그 파일**: /devdata/apps/bridge-db-monitoring/logs/resource-monitor.csv

### 서버 최적화 설정

#### JVM 옵션 (자동 계산)
```bash
-Xms${JVM_HEAP}g -Xmx${JVM_HEAP}g 
-XX:+UseG1GC 
-XX:MaxGCPauseMillis=200 
-XX:+UseStringDeduplication 
-XX:+ParallelRefProcEnabled 
-XX:ParallelGCThreads=$((CPU_CORES / 2)) 
-XX:ConcGCThreads=$((CPU_CORES / 4))
```

#### 병렬 처리 설정
- Partition Size: CPU 코어 수 × 2
- Parallel Threads: CPU 코어 수 ÷ 2

### 로그 및 모니터링

#### 로그 파일 위치
- 애플리케이션 로그: /devdata/apps/bridge-db-monitoring/logs/app.log
- 리소스 모니터링: /devdata/apps/bridge-db-monitoring/logs/resource-monitor.csv
- Heap Dump: /devdata/apps/bridge-db-monitoring/logs/heapdump.hprof

#### 모니터링 URL
- Health Check: http://10.26.252.48:8090/actuator/health
- Admin Dashboard: http://10.26.252.48:8090/static/admin/batch-admin.html
- WebSocket Test: http://10.26.252.48:8090/websocket/track-streaming-test.html
- Abnormal Tracks: http://10.26.252.48:8090/static/admin/abnormal-tracks.html

### 배치 작업
1. **5분 주기 위치 집계**: vesselAggregationJob (매 5분 3분째)
2. **5분 주기 항적 집계**: vesselTrackAggregationJob (매 5분 4분째)
3. **1시간 주기 집계**: hourlyAggregationJob (매시 10분)
4. **1일 주기 집계**: dailyAggregationJob (매일 01:00)

## 주요 테이블 구조

### 5분 단위 테이블
- **t_vessel_tracks_5min**: 5분 단위 전체 항적 (LineStringM)
- **t_grid_vessel_tracks**: 해구별 선박 항적
- **t_area_vessel_tracks**: 구역별 선박 항적
- **t_grid_tracks_summary**: 해구별 집계 요약
- **t_area_tracks_summary**: 구역별 집계 요약

### 1시간 단위 테이블
- **t_vessel_tracks_hourly**: 시간별 전체 항적
- **t_grid_vessel_tracks_hourly**: 시간별 해구 항적
- **t_grid_tracks_summary_hourly**: 시간별 해구 집계
- **t_area_tracks_summary_hourly**: 시간별 구역 집계

### 1일 단위 테이블
- **t_vessel_tracks_daily**: 일별 전체 항적
- **t_grid_vessel_tracks_daily**: 일별 해구 항적
- **t_grid_tracks_summary_daily**: 일별 해구 집계
- **t_area_tracks_summary_daily**: 일별 구역 집계

### 위치 집계 테이블
- **t_tile_summary**: 타일별 선박 위치 집계 (5분 단위)
- **t_area_statistics**: 구역별 선박 통계

### 비정상 항적 테이블
- **t_abnormal_tracks**: 비정상 항적 원본 데이터 (실시간 검출)
- **t_abnormal_track_stats**: 비정상 항적 통계 (5분/시간/일 단위)

### 기타 테이블
- **sig_test**: 원본 선박 데이터 (collectDB, 일별 파티션)
- **t_grid_tiles**: 타일 정의 (대해구/소해구)
- **t_haegu_definitions**: 대해구 정의
- **t_areas**: 사용자 정의 구역

## 모니터링 시스템

### 모니터링 페이지 구성
1. **Dashboard** (`/static/admin/batch-admin.html`)
   - 실시간 Job 실행 상태
   - 24시간 처리 통계
   - 항적 집계 메트릭
   - 시스템 리소스 현황
   - WebSocket Test 탭 (iframe으로 통합)

2. **GIS Monitoring**
   - 해구/영역별 선박 분포 시각화
   - 선박 수에 따른 색상 단계 표시:
     - 0척: 매우 연한 회색
     - 1-9척: 연한 회색
     - 10-49척: 연한 녹색
     - 50-99척: 금색
     - 100-199척: 주황색
     - 200척+: 진한 빨강
   - 클릭시 해당 영역 선박 항적 조회
   - 로컬 타일 서버 사용 (`/devdata/MAPS/WORLD_webp/`)

### 모니터링 API 엔드포인트

#### GIS 관련 API
| 엔드포인트 | 설명 | 응답 형식 |
|------------|------|----------|
| GET /api/v1/haegu/boundaries | 모든 해구 경계 조회 | GeoJSON |
| GET /api/v1/haegu/vessel-stats?minutes={n} | 해구별 선박 통계 | Map<Integer, VesselStatsResponse> |
| GET /api/v1/areas/boundaries | 사용자 정의 영역 경계 | GeoJSON |
| GET /api/v1/areas/vessel-stats?minutes={n} | 영역별 선박 통계 | Map<String, VesselStatsResponse> |
| GET /api/v1/tracks/haegu/{haeguNo}?minutes={n} | 해구 내 선박 항적 | List<TrackResponse> |
| GET /api/v1/tracks/area/{areaId}?minutes={n} | 영역 내 선박 항적 | List<TrackResponse> |

#### 타일 서비스 API
| 엔드포인트 | 설명 |
|------------|------|
| GET /api/tiles/world/{z}/{x}/{y}.webp | 지도 타일 제공 |
| GET /api/tiles/health | 타일 서비스 상태 확인 |

#### 배치 모니터링 API
| 엔드포인트 | 설명 |
|------------|------|
| GET /admin/batch/job/running | 실행 중인 Job 목록 |
| GET /admin/batch/statistics?days={n} | 처리 통계 |
| GET /admin/batch/job/history | Job 실행 이력 |
| GET /monitor/tracks/status?hours={n} | 항적 집계 상태 |
| GET /monitor/tracks/quality | 항적 품질 메트릭 |
| GET /admin/metrics/summary | 시스템 메트릭 |

### 데이터 응답 구조

#### VesselStatsResponse
```json
{
  "vessel_count": 125,
  "total_distance": 2456.78,
  "avg_speed": 12.5,
  "active_tracks": 150
}
```

#### TrackResponse
```json
{
  "sig_src_cd": "AIS",
  "target_id": "123456789",
  "time_bucket": "2025-07-20T09:45:00",
  "track_geom": "LINESTRING M(...)",
  "distance_nm": 45.67,
  "avg_speed": 14.2,
  "max_speed": 18.5,
  "point_count": 12
}
```

### 모니터링 데이터 흐름
```
집계 테이블 → GisService → Controller → deck.gl/maplibre-gl → 시각화
     ↓
t_grid_vessel_tracks (5분)
t_area_vessel_tracks (5분)
t_grid_tracks_summary_hourly (1시간)
t_area_tracks_summary_daily (1일)
```

#### GIS Monitoring 향후 개선사항
- [ ] **다중 선박 항적 선택**: 여러 선박의 항적을 동시에 표시하는 기능
- [ ] **밀도 히트맵**: 선박 밀도를 히트맵으로 시각화
- [ ] **실시간 업데이트**: WebSocket/SSE를 통한 실시간 위치 업데이트
- [ ] **항적 애니메이션**: 시간 흐름에 따른 선박 이동 애니메이션
- [ ] **필터링 기능**: 선박 타입, 속도, 국적 등으로 필터링
- [ ] **항적 내보내기**: 선택한 항적 데이터를 GeoJSON/KML로 내보내기

## 향후 API 설계 시 고려사항
1. **시간 단위 유연성**: 5분/1시간/1일 데이터를 통합 조회하는 API 필요
2. **대용량 LineStringM 처리**: WebSocket 스트리밍 응답 구현
3. **캐싱 전략**: 해구/영역 경계는 변경이 적으므로 캐싱 적용
4. **실시간성**: WebSocket(STOMP)을 통한 실시간 업데이트
5. **권한 관리**: 민감한 선박 정보에 대한 접근 제어
6. **성능 최적화**: 공간 인덱스 활용, 집계 데이터 우선 조회

## 계획중인 기능 개발 (2025-07-24 추가)

### 🎯 비정상 항적 필터링 및 분리 저장 기능
**문제점**: GIS 렌더링 시 물리적으로 불가능한 위치로 순간이동하는 항적 다수 발견

**개발 내용**:
- [ ] **비정상 항적 검출 시스템**
  - 단일 time_bucket 내 비정상 항적 검출 (속도 > 50kts, 거리 > 10nm/5분)
  - time_bucket 간 연결 지점 검증 (이전 bucket 마지막 → 다음 bucket 첫 지점)
  - AbnormalTrackDetector 컴포넌트 구현
  
- [ ] **별도 테이블 관리**
  - t_abnormal_tracks: 비정상 항적 원본 저장
  - t_abnormal_track_stats: 비정상 항적 통계
  - 비정상 유형 분류: excessive_speed, teleport, gap_jump
  
- [ ] **배치 Job 수정**
  - HourlyAggregationJob: 5분→1시간 집계 시 비정상 검출
  - DailyAggregationJob: 1시간→1일 집계 시 비정상 검출
  - 정상 항적만 집계 테이블에 저장

**예상 일정**: 13일 (Phase1: 3일, Phase2: 5일, Phase3: 3일, Phase4: 2일)


## 현재 이슈 및 TODO

### 🟢 진행중
- [ ] **비정상 항적 모니터링 개선**:
  - 동일 선박의 반복 비정상 패턴 분석 필요
  - 시각화 및 상세 분석 기능 강화 예정

### ✅ 완료된 작업
- [x] **성능 최적화 컴포넌트 통합** (2025-07-21 22:30)
  - 동적 청크 크기 조정
  - 메모리 최적화
  - 캐시 최적화
  - 성능 모니터링 API

- [x] **데이터베이스 인덱스 최적화** (2025-07-21 22:30)
  - 10개 권장 인덱스 생성 완료
  - 파티션 테이블 인덱스 전략 수립
  - 인덱스 사용 통계 모니터링

- [x] **WebSocket 항적 스트리밍 API** (2025-07-21)
  - 완전한 STOMP over WebSocket 구현
  - 대용량 데이터 처리를 위한 병렬 스트리밍
  - batch-admin 대시보드 통합 완료
  - 문서화 및 가이드 작성 완료

### 🔴 향후 개선사항
- [ ] **고급 성능 최적화**
  - 병렬 처리 전략 고도화
  - 파티션 전략 최적화
  - 쿼리 플랜 분석 및 개선

- [ ] **데이터 보존 정책 구현**
  - 5분 데이터: 7일 후 자동 삭제
  - 1시간 데이터: 30일 후 아카이빙
  - 1일 데이터: 영구 보관
  - 자동 정리 스케줄러 구현

- [ ] **GIS 모니터링 고도화**
  - 선박 밀집도 시각화 (히트맵)
  - 실시간 운항 현황 대시보드
  - 시간대별 트래픽 분석

### 📅 Job 실행 스케줄

| Job 명 | 실행 주기 | 실행 시간 | 대상 데이터 | 상태 |
|--------|----------|------------|--------------|------|
| vesselAggregationJob | 5분 | 03, 08, 13, 18... | 최근 5분 | ✅ 운영중 |
| vesselTrackAggregationJob | 5분 | 04, 09, 14, 19... | 최근 5분 | ✅ 운영중 |
| hourlyAggregationJob | 1시간 | 매시 10분 | 이전 시간 | ✅ 운영중 |
| dailyAggregationJob | 1일 | 매일 01:00 | 전일 | ✅ 운영중 |

### 수정된 파일 목록 (2025-07-25)
1. **HourlyTrackProcessor.java**: start/end position 설정, M값 재계산
2. **DailyTrackProcessor.java**: start/end position 설정, M값 재계산
3. **LineStringMUtils.java**: M값 재계산 유틸리티 (신규)
4. **AbnormalTrackDetector.java**: String geometry 처리
5. **AbnormalTrackWriter.java**: model.VesselTrack 사용
6. **CompositeTrackWriter.java**: AbnormalDetectionResult 처리
7. **DailyTrackProcessorWithAbnormalDetection.java**: 인터페이스 타입 사용
8. **HourlyTrackProcessorWithAbnormalDetection.java**: 인터페이스 타입 사용
9. **DailyAggregationStepConfig.java**: 중복 Bean 제거, Summary 클래스 추가
10. **HourlyAggregationStepConfig.java**: 중복 Bean 제거, Summary 클래스 추가

## 개발 시 준수 사항

### 시간 범위별 테이블 선택 전략 (필수)

조회 기능을 개발할 때는 **반드시** 조회 기간에 따라 적절한 집계 테이블을 동적으로 선택해야 합니다. 이는 성능 최적화와 데이터 신선도를 보장하기 위한 핵심 전략입니다.

#### 기본 원칙
1. **최신 데이터는 세밀한 테이블에서**: 최근 데이터일수록 더 세밀한 집계 테이블 사용
2. **과거 데이터는 집계된 테이블에서**: 오래된 데이터는 이미 집계된 테이블 사용
3. **경계 시점 고려**: 각 집계 레벨의 경계 시점을 정확히 계산

#### 테이블 선택 기준

| 데이터 범위 | 사용 테이블 | 집계 주기 | 사용 시점 |
|------------|-------------|-----------|----------|
| 최근 1시간 | 5분 테이블 | 5분 | 실시간 ~ 1시간 전 |
| 1시간 ~ 24시간 | 1시간 테이블 | 1시간 | 정각 기준 완료된 시간 |
| 24시간 이상 | 1일 테이블 | 1일 | 자정 기준 완료된 날짜 |

#### 구현 예시

##### 예시 1: 3일간 데이터 조회 (현재: 2025-07-23 14:35)
```sql
-- /조회 요청: 2025-07-20 14:35 ~ 2025-07-23 14:35

-- 1. 1일 테이블에서 조회 (완료된 일자)
-- 기간: 2025-07-20 00:00 ~ 2025-07-22 23:59:59
SELECT * FROM t_vessel_tracks_daily 
WHERE time_bucket >= '2025-07-20' AND time_bucket < '2025-07-23';

-- 2. 1시간 테이블에서 조회 (오늘의 완료된 시간)
-- 기간: 2025-07-23 00:00 ~ 2025-07-23 13:59:59
SELECT * FROM t_vessel_tracks_hourly 
WHERE time_bucket >= '2025-07-23 00:00' AND time_bucket < '2025-07-23 14:00';

-- 3. 5분 테이블에서 조회 (현재 시간대)
-- 기간: 2025-07-23 14:00 ~ 2025-07-23 14:35 (실제로는 14:30까지만 존재)
SELECT * FROM t_vessel_tracks_5min 
WHERE time_bucket >= '2025-07-23 14:00' AND time_bucket < '2025-07-23 14:35';
```

##### 예시 2: 7일간 데이터 조회
```sql
-- 1. 대부분의 데이터는 1일 테이블에서
SELECT * FROM t_vessel_tracks_daily WHERE ...;

-- 2. 오늘의 데이터만 1시간 테이블에서
SELECT * FROM t_vessel_tracks_hourly WHERE ...;

-- 3. 최근 1시간은 5분 테이블에서
SELECT * FROM t_vessel_tracks_5min WHERE ...;
```

#### 주의사항

1. **집계 지연 고려**
   - 5분 집계: 실행 시점 기준 5~10분 지연
   - 1시간 집계: 매시 10분에 실행 (이전 시간 데이터)
   - 1일 집계: 매일 01:00에 실행 (전일 데이터)

2. **중복 데이터 방지**
   - 각 테이블의 시간 범위가 겹치지 않도록 정확한 경계 설정
   - time_bucket 컬럼의 타임스탬프를 기준으로 필터링

3. **Null 데이터 처리**
   - 집계가 아직 완료되지 않은 시간대는 결과가 없을 수 있음
   - 적절한 폴백 로직 구현 필요

4. **성능 최적화**
   - UNION ALL 사용 시 각 서브쿼리에 인덱스 활용
   - 병렬 쿼리 실행으로 응답 시간 단축
   - 필요시 결과 캐싱 적용

#### StompTrackStreamingService 참고 구현
```java
// 시간 범위별 테이블 전략 분할
private Map<TableStrategy, List<TimeRange>> splitTimeRangeByStrategy(
        LocalDateTime start, LocalDateTime end) {
    Map<TableStrategy, List<TimeRange>> strategyMap = new LinkedHashMap<>();
    LocalDateTime now = LocalDateTime.now();
    
    // 5분 데이터: 최근 1시간 이내
    LocalDateTime hourAgo = now.minusHours(1);
    
    // 1시간 데이터: 1시간 전 ~ 24시간 전  
    LocalDateTime dayAgo = now.minusDays(1);
    
    // 1일 데이터: 24시간 이전
    // ... 구현 로직
}
```

이 전략을 따르지 않을 경우:
- ❌ 대량 데이터 조회 시 성능 저하
- ❌ 불필요한 세밀한 데이터 조회로 메모리 낭비
- ❌ 집계되지 않은 최신 데이터 누락

## Dev 환경 설정 (application-dev.yml)
```yaml
vessel:
  batch:
    chunk-size: 5000
    page-size: 5000
    partition-size: 12
    fetch-size: 200000
    bulk-insert:
      batch-size: 5000
      parallel-threads: 4
    optimization:
      enabled: true
      dynamic-chunk-sizing: true
      memory-optimization: true
      cache-optimization: true
```

## 실행 방법

### 개발 환경 빌드 및 테스트
```bash
# Dev 프로필로 빌드
mvn clean package -DskipTests

# 로컬 테스트 실행
java -Xms31g -Xmx31g -Dspring.profiles.active=dev -jar target/signal_batch-1.0.0.jar
```

### 운영 서버 배포 및 실행
```bash
# 1. 로컬에서 빌드
cd C:\Users\lht87\IdeaProjects\signal_batch
mvn clean package -DskipTests

# 2. 서버로 JAR 파일 전송
scp target/signal_batch-1.0.0.jar user@10.26.252.48:/devdata/apps/bridge-db-monitoring/vessel-batch-aggregation.jar

# 3. 서버에서 실행
ssh user@10.26.252.48
cd /devdata/apps/bridge-db-monitoring
./vessel-batch-control.sh start

# 4. 상태 확인
./vessel-batch-control.sh status

# 5. 실시간 모니터링
./monitor-query-server.sh
```

### 특수 실행 모드
```bash
# 인덱스 생성 모드
java -jar vessel-batch-aggregation.jar --spring.profiles.active=dev --create.indexes=true

# 인덱스 상태 확인
java -jar vessel-batch-aggregation.jar --spring.profiles.active=dev --check.index.status=true
```

## 주요 API 엔드포인트
| 기능 | Method | URL |
|------|--------|-----|
| Health Check | GET | http://10.26.252.48:8090/actuator/health |
| 대해구 통계 | GET | http://10.26.252.48:8090/admin/haegu/stats |
| 실시간 현황 | GET | http://10.26.252.48:8090/monitor/haegu/realtime |
| 타일 집계 API | GET | http://10.26.252.48:8090/api/v1/tiles/aggregation |
| WebSocket 테스트 | GET | http://10.26.252.48:8090/api/websocket/test |
| WebSocket 상태 | GET | http://10.26.252.48:8090/api/websocket/status |
| WebSocket GIS 테스트 | GET | http://10.26.252.48:8090/websocket/track-streaming-gis.html |
| 성능 최적화 상태 | GET | http://10.26.252.48:8090/api/v1/performance/status |
| 성능 리포트 | GET | http://10.26.252.48:8090/api/v1/performance/report |
| 인덱스 상태 | GET | http://10.26.252.48:8090/api/v1/performance/index/current-status |
| 비정상 항적 통계 | GET | http://10.26.252.48:8090/api/v1/abnormal-tracks/statistics/summary |
| 비정상 항적 조회 | GET | http://10.26.252.48:8090/api/v1/abnormal-tracks/recent |
| Swagger UI | - | http://10.26.252.48:8090/swagger-ui.html |

## 성능 관련 참고사항
1. **처리량**: 5분당 약 25,000건 처리
2. **메모리**: 실제 사용량 500-600MB (31GB 할당)
3. **DB 쿼리 최적화**: 300,000회 → 0회 (캐싱 적용)
4. **처리 속도**: 5-10배 향상 (AreaBoundaryCache 적용)
5. **인덱스 최적화 효과** (2025-07-21 적용):
   - WebSocket API 조회: 5-10배 성능 향상 예상
   - GIS 모니터링 조회: 3-5배 성능 향상 예상
   - 배치 집계 처리: 2-3배 성능 향상 예상

## 시스템 안정성
- **배치 스케줄**: 자동 실행 및 동시 실행 방지
- **재시도**: 중복 키 발생 시 자동 UPSERT 전환
- **메모리 처리**: 5분간 데이터를 메모리에 로드 후 처리
- **커넥션 풀**: collect(20), query(40), batch(30)
- **Bean 설정**: allow-bean-definition-overriding: true
- **성능 최적화**: 동적 청크 크기 조정, 캐시 히트율 모니터링
- **인덱스 관리**: 파티션 테이블 인덱스 자동 상속

## WebSocket API 사용 가이드

### WebSocket 연결
- **엔드포인트**: ws://10.26.252.48:8090/ws-tracks
- **프로토콜**: STOMP over WebSocket

### STOMP Destinations
- **쿼리 요청**: `/app/tracks/query`
- **쿼리 취소**: `/app/tracks/cancel/{queryId}`
- **하트비트**: `/app/heartbeat`

### 구독 채널
- **항적 데이터**: `/user/queue/tracks/data`
- **상태 업데이트**: `/user/queue/tracks/status`
- **응답**: `/user/queue/tracks/response`
- **오류**: `/user/queue/errors`

### 요청 예시
```json
{
  "startTime": "2025-01-19T00:00:00",
  "endTime": "2025-01-21T00:00:00",
  "viewport": {
    "minLon": 124.0,
    "maxLon": 132.0,
    "minLat": 33.0,
    "maxLat": 38.0
  },
  "chunkSize": 2000,
  "simplificationTolerance": 0.0001
}
```

---
최종 수정: 2025-07-28 17:00
작성자: System (Claude 지원)
