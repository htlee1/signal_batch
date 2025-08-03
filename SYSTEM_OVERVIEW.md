# Vessel Batch Aggregation System Overview

## 시스템 개요

### 목적
실시간으로 수집되는 대용량 선박 위치 데이터를 계층적으로 집계하여 빠른 조회 성능을 제공하는 배치 처리 시스템

### 핵심 기능
1. **실시간 데이터 집계**: 5분 단위로 선박 위치/항적 집계
2. **계층적 집계**: 5분 → 1시간 → 1일 단계적 집계
3. **공간 기반 집계**: 해구(대해구/소해구) 및 사용자 정의 영역별 집계
4. **항적 데이터 관리**: LineStringM 형식의 시공간 항적 저장
5. **비정상 항적 검출**: 물리적 불가능 항적 자동 필터링
6. **과거 항적 조회 및 리플레이**: 조회기간과 범위를 입력받아 해당 기간동안의 선박 이동 항적 추출 (영역 범위, 줌 레벨에 따른 간소화 )

## 시스템 아키텍처

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   CollectDB     │────▶│  Spring Batch    │────▶│    QueryDB      │
│ (실시간 데이터) │     │  (집계 처리)     │     │  (집계 데이터)  │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌──────────────────┐
                        │    BatchDB       │
                        │ (메타데이터)     │
                        └──────────────────┘
```

## 프로젝트 구조
```
signal_batch/
├── src/main/java/gc/mda/signal_batch/
│   ├── common/          # 공통 컴포넌트 (DataHolder, Cache 등)
│   ├── config/          # 설정 클래스
│   ├── controller/      # REST API 컨트롤러
│   │   └── websocket/   # Stomp Websocket 컨트롤러
│   ├── dto/            # dto
│   │   └── websocket/   # Stomp Websocket dto
│   ├── job/            # 배치 Job 설정
│   │   ├── listener/   # Job listener
│   │   ├── scheduler/  # Job scheduler
│   │   └── step/       # Job step
│   ├── model/            # model
│   ├── performance/    # 데이터 처리 성능 개선 도구
│   ├── processor/      # 데이터 처리 로직
│   ├── reader/         # 데이터 읽기 로직
│   ├── service/         # 서비스 로직
│   │   ├── filter/   # 비정상항적 필터, 항적 간소화 필터
│   │   ├── optimization/  # 항적 스트리밍 서비스
│   │   ├── query/  # 쿼리 처리 서비스 
│   │   └── simplification/       # 항적 간소화 서비스
│   ├── writer/         # 데이터 쓰기 로직
│   ├── util/           # 유틸리티 클래스
│   ├── websocket/         # websocket
│   │   ├── handler/       # handler
│   │   └── interceptor/       # interceptor
└── src/main/resources/
    └── static/         # 웹 UI (모니터링 대시보드)
        ├── admin/   # 모니터링 메인 페이지
        ├── js/  # 모니터링 페이지 js 로직
        ├── libs/  # cdn 외부 참조 css, js 로컬라이징 
        └── websocket/       # websocket api 테스트용 페이지
    
```

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
5분 데이터
    └─▶ hourlyAggregationJob (매시 10분)
        └─▶ 1시간 데이터
            └─▶ dailyAggregationJob (매일 01:00)
                └─▶ 1일 데이터
```

## 주요 테이블 구조

### 항적 테이블 계층
| 집계 단위 | 전체 항적 | 해구별 항적 | 영역별 항적 |
|-----------|-----------|-------------|-------------|
| 5분 | t_vessel_tracks_5min | t_grid_vessel_tracks | t_area_vessel_tracks |
| 1시간 | t_vessel_tracks_hourly | t_grid_tracks_summary_hourly | t_area_tracks_summary_hourly |
| 1일 | t_vessel_tracks_daily | t_grid_tracks_summary_daily | t_area_tracks_summary_daily |

### 데이터 타입
- **항적**: PostGIS LineStringM (X, Y, M) - M값은 시간
- **위치**: JSONB {lat, lon, time, sog}
- **통계**: vessel_count, total_distance, avg_speed
- **속도 계산**: ST_Length(geography) 기반 실제 거리/시간 (2025-07-25)

## Job 실행 스케줄

| Job | 실행 주기 | 실행 시간 | 처리 데이터 |
|-----|----------|-----------|-------------|
| vesselAggregationJob | 5분 | 3, 8, 13, 18... | 최근 5분 위치 |
| vesselTrackAggregationJob | 5분 | 4, 9, 14, 19... | 최근 5분 항적 |
| hourlyAggregationJob | 1시간 | 매시 10분 | 이전 시간 데이터 |
| dailyAggregationJob | 1일 | 매일 01:00 | 전일 데이터 |

## 성능 최적화 전략

### 1. 메모리 기반 처리
- VesselDataHolder를 통한 배치 내 데이터 공유
- InMemoryReader로 DB 쿼리 최소화

### 2. 병렬 처리
- 파티션 단위 병렬 실행
- Bulk Insert/Copy 활용

### 3. 공간 인덱싱
- PostGIS 공간 인덱스 활용
- 해구 경계 메모리 캐싱

### 4. 테이블 파티셔닝
- 일/월 단위 파티션
- 자동 파티션 관리

### 5. 항적 간소화 (2025-07-29 추가)
- **TrackSimplificationUtils** 생성
- **Hourly 집계**: 10m 이내 이동 생략, 최대 10분 간격
- **Daily 집계**: 20m 이내 이동 생략, 최대 30분 간격
- LineStringM 형식 유지하면서 중복 포인트 제거
- 정박/저속 운항 선박의 데이터 효율성 향상

## 모니터링 및 운영

### 대시보드
- **URL**: http://10.26.252.48:8090/static/admin/batch-admin.html
- Job 실행 상태, 처리 통계, 시스템 리소스 모니터링
- **탭 구성**:
  - Dashboard: 실시간 현황 및 통계
  - Job Management: 배치 Job 관리
  - Execution History: 실행 이력 조회
  - Monitoring: 실시간 성능 모니터링
  - GIS Monitoring: 해구/영역별 선박 분포 시각화
  - **WebSocket Test**: 항적 스트리밍 API 테스트 (2025-07-21 추가)
  - **WebSocket GIS**: 스트리밍 항적 실시간 렌더링 (2025-07-24 추가)
  - **Load Test**: 부하 테스트 도구 (2025-07-21 추가)
  - **Abnormal Tracks**: 비정상 항적 모니터링 (2025-07-25 완료, 2025-07-28 UI/UX 개선)
  - Settings: 시스템 설정

### GIS 모니터링 (2025-07-21 개선)
- **해구/영역별 선박 분포 시각화**
  - 선박 수에 따른 색상 단계별 표시
  - 실시간 선박 위치 및 항적 표시
  - 해구/영역별 전체 선박 항적 일괄 표시
- **고급 항적 관리**
  - 다중 선박 선택 및 동시 항적 표시
  - 속도/거리 기반 필터링 (0-50 kts, 0-200 nm)
  - ID/속도/거리 기준 정렬 (오름차순/내림차순)
- **향상된 UI/UX**
  - 네비게이션 개선 (Back to List, Back to Area Info)
  - 패널 위치 최적화 (1920x1080 기준)
  - deck.gl 기반 고성능 렌더링

### WebSocket Test 모니터링 (2025-07-21 추가, 2025-07-23 강화)
- **대용량 항적 데이터 스트리밍 테스트**
  - STOMP over WebSocket 프로토콜 사용
  - 실시간 진행률 및 상태 표시
  - 필터링 옵션 (viewport, 해구, 영역, 선박)
  - 쿼리 취소 기능
- **고급 필터링 기능 (2025-07-23 추가)**
  - 거리/속도 기반 필터링
    - 최소/최대 전체 이동거리 (nm)
    - 최소/최대 평균속도 (knots)
    - Bucket 간 거리 포함 옵션
  - VesselTrackFilter 컴포넌트로 정확한 계산
- **통합 방식**
  - batch-admin 대시보드에 iframe으로 통합
  - 별도 페이지 이동 없이 사용 가능
- **성능 최적화**
  - 병렬 쿼리 처리로 30일 데이터도 신속하게 스트리밍
  - 시간 범위에 따른 자동 테이블 선택
  - 필터링 결과 캐싱으로 성능 향상

### 비정상 항적 모니터링 (2025-07-28 완료, 2025-07-29 강화)
- **집계 메트릭 기반 비정상 검출**
  - 평균속도(avg_speed) 및 이동거리(distance_nm) 기반 판단
  - 시간 비례 거리 검증 자동화
  - maxSpeed 물리적 한계 적용 (선박 100knots, 항공기 300knots)
  - 비정상 항적 완전 제외 처리
  - **5분 집계**: 선박 100 knots/10nm, 항공기 300 knots/30nm 이상 필터링 (2025-07-29 강화)
  - **SOG 없거나 0인 경우**: avgSpeed 기반으로만 판단
  - **Hourly/Daily 집계**: Bucket 간 연결점만 검사, 선박/항공기 분리 기준 (2025-07-29 개선)
- **분리 저장 및 통계**
  - t_abnormal_tracks: 비정상 항적 원본 저장
  - t_abnormal_track_stats: 비정상 항적 통계
  - 비정상 유형별 통계 및 추이 분석
- **대시보드 기능**
  - 실시간 비정상 항적 통계 표시 (조회 기간 기반 동적 집계)
  - 선박별 그룹화 및 다중 선택 기능
  - 지도-리스트 상호 작용 (양방향 포커스/스크롤)
  - 선택된 항적 강조 표시 (최상단 렌더링, 굵은 선)
  - 항적 포인트별 상세정보 툴팁
  - 필터링 기능 (기간, 유형, 선박 ID)
- **UI/UX 개선 (2025-07-28)**
  - 마우스 오버 시 하이라이트 유지
  - 클릭 시 강조 상태 지속
  - 선박 그룹 하위 선택 시 자동 그룹 활성화
  - 선택된 트랙 자동 스크롤 및 하이라이트
  - 통계/범례 일관성 (필터링된 데이터 기반)
- **성능 및 설정**
  - 배치 Job 실행 시 자동 검출
  - 캐싱을 통한 고속 처리
  - application-dev.yml에서 임곀4값 설정 가능
  - 로그 레벨 debug로 조정 가능

### 주요 메트릭
- 처리량: 5분당 약 25,000건
- 메모리: 500-600MB (31GB 할당)
- 저장 용량: 일 2-3GB 증가

## 향후 확장 계획

### 진행 예정 (2025-07 말)
- [ ] **WebSocket API 성능 개선**
  - 메모리 효율성 및 스트리밍 최적화
  - 항적 간소화 알고리즘 개선
  
- [ ] **파티션 관리 자동화**
  - 비정상 항적 테이블 파티셔닝
  - 자동 파티션 생성/삭제 스크립트

### Phase 1 (현재 완료)
- ✅ 기본 집계 시스템 구축
- ✅ 계층적 집계 구현
- ✅ GIS 기반 모니터링

### Phase 2 (진행중)
- ✅ 항적 조회 API 개발 (WebSocket 스트맍밍) - **완료 (2025-07-21)**
- ✅ 비정상 항적 검출 및 모니터링 - **완료 (2025-07-25)**
- 🔄 비정상 항적 모니터링 UI 고도화
  - 선박ID 기준 그룹화 및 패턴 분석
  - 지도 상호작용 기능 강화
  - 포인트별 상세정보 툴팁
- 🔄 실시간 스트리밍 처리

### Phase 3 (계획)
- 📋 예측 분석 (항로 예측, 패턴 학습)
- 📋 다중 데이터 소스 통합
- 📋 글로벌 확장 (다중 리전)

---
