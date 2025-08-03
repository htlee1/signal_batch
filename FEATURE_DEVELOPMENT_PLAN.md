# 선박 궤적 시스템 기능 개선 개발 계획

## 1. 비정상 궤적 필터링 및 분리 저장 기능

### 1.1 문제 정의
- **현상**: GIS 렌더링 시 물리적으로 불가능한 위치로 순간이동하는 궤적 다수 발견
- **원인**: 
  - GPS 신호 오류, 데이터 전송 오류
  - 선박 식별자 중복 또는 변경
  - 시간 동기화 문제
- **영향**: 궤적 시각화 품질 저하, 통계 정확도 감소

### 1.2 비정상 궤적 판별 기준

#### 1.2.1 단일 time_bucket 내 비정상 궤적
```
- 최대 속도 임계값: 50 knots (약 92.6 km/h)
- 순간 이동 거리 임계값: 10 nm (약 18.5 km) / 5분
- 가속도 임계값: 10 knots/분
```

#### 1.2.2 time_bucket 간 연결 지점 검증
```
- 이전 bucket 마지막 지점 → 다음 bucket 첫 지점
- 시간 간격 대비 거리 계산
- 물리적 가능 속도 초과 시 비정상 판정
```

### 1.3 데이터베이스 설계

#### 1.3.1 비정상 궤적 테이블
```sql
-- 비정상 궤적 원본 저장
CREATE TABLE t_abnormal_tracks (
    id BIGSERIAL PRIMARY KEY,
    sig_src_cd VARCHAR(10),
    target_id VARCHAR(20),
    time_bucket TIMESTAMP,
    track_geom geometry(LineStringM, 4326),
    abnormal_type VARCHAR(50), -- 'excessive_speed', 'teleport', 'gap_jump'
    abnormal_reason JSONB, -- 상세 이유 (거리, 속도, 시간차 등)
    distance_nm NUMERIC(10,2),
    avg_speed NUMERIC(6,2),
    max_speed NUMERIC(6,2),
    point_count INTEGER,
    source_table VARCHAR(50), -- 원본 테이블 (5min/hourly/daily)
    detected_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT pk_abnormal_tracks UNIQUE (sig_src_cd, target_id, time_bucket, source_table)
);

-- 비정상 궤적 통계
CREATE TABLE t_abnormal_track_stats (
    stat_date DATE,
    abnormal_type VARCHAR(50),
    vessel_count INTEGER,
    track_count INTEGER,
    total_points INTEGER,
    avg_deviation NUMERIC(10,2),
    created_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (stat_date, abnormal_type)
);

-- 인덱스
CREATE INDEX idx_abnormal_tracks_time ON t_abnormal_tracks(time_bucket);
CREATE INDEX idx_abnormal_tracks_vessel ON t_abnormal_tracks(sig_src_cd, target_id);
CREATE INDEX idx_abnormal_tracks_type ON t_abnormal_tracks(abnormal_type);
```

### 1.4 구현 계획

#### 1.4.1 AbnormalTrackDetector 컴포넌트
```java
@Component
public class AbnormalTrackDetector {
    
    private static final double MAX_SPEED_KNOTS = 50.0;
    private static final double MAX_DISTANCE_5MIN_NM = 10.0;
    private static final double MAX_ACCELERATION_KNOTS_PER_MIN = 10.0;
    
    public AbnormalDetectionResult detectAbnormalTrack(
            VesselTrack track, 
            VesselTrack previousTrack) {
        
        List<AbnormalSegment> abnormalSegments = new ArrayList<>();
        
        // 1. 단일 궤적 내 검증
        abnormalSegments.addAll(checkInternalConsistency(track));
        
        // 2. 궤적 간 연결 지점 검증
        if (previousTrack != null) {
            abnormalSegments.addAll(checkBucketTransition(previousTrack, track));
        }
        
        return new AbnormalDetectionResult(track, abnormalSegments);
    }
    
    private List<AbnormalSegment> checkInternalConsistency(VesselTrack track) {
        // 속도, 가속도, 순간이동 검증
    }
    
    private List<AbnormalSegment> checkBucketTransition(
            VesselTrack prev, VesselTrack current) {
        // bucket 간 연결 지점 검증
    }
}
```

#### 1.4.2 수정된 Processor
```java
@Component
public class EnhancedVesselTrackProcessor {
    
    @Autowired
    private AbnormalTrackDetector abnormalTrackDetector;
    
    @Override
    public ProcessResult process(VesselTrackData data) {
        // 기존 처리 로직
        VesselTrack track = buildTrack(data);
        
        // 비정상 궤적 검출
        AbnormalDetectionResult detection = abnormalTrackDetector.detectAbnormalTrack(
            track, 
            getPreviousTrack(data.getVesselId())
        );
        
        if (detection.hasAbnormalities()) {
            // 비정상 부분 제거 또는 보정
            track = detection.getCorrectedTrack();
            
            // 비정상 궤적 별도 저장
            return ProcessResult.builder()
                .normalTrack(track)
                .abnormalSegments(detection.getAbnormalSegments())
                .build();
        }
        
        return ProcessResult.builder()
            .normalTrack(track)
            .build();
    }
}
```

#### 1.4.3 배치 Job 수정
- HourlyAggregationJob: 5분 데이터 집계 시 비정상 검출
- DailyAggregationJob: 1시간 데이터 집계 시 비정상 검출
- 새로운 Writer 추가: AbnormalTrackWriter

### 1.5 개발 일정
- **Phase 1** (3일): 데이터베이스 설계 및 테이블 생성
- **Phase 2** (5일): AbnormalTrackDetector 구현 및 단위 테스트
- **Phase 3** (3일): 배치 Job 통합 및 테스트
- **Phase 4** (2일): 모니터링 대시보드 추가
- **총 소요**: 13일

---

## 2. 대용량 데이터 조회 시 궤적 간소화 기능

### 2.1 문제 정의
- **현상**: 1일 이상 데이터 조회 시 버퍼 오버플로우
- **원인**: 
  - 너무 많은 궤적 포인트 (7일 = 약 2,016개 포인트/선박)
  - 메모리 및 네트워크 대역폭 한계
- **요구사항**: 7일, 15일, 30일 조회 지원

### 2.2 간소화 전략

#### 2.2.1 시간 범위별 간소화 레벨
```
- 1일 이하: 원본 데이터 (간소화 없음)
- 1-7일: 경량 간소화 (50% 포인트 유지)
- 7-15일: 중간 간소화 (25% 포인트 유지)
- 15-30일: 고도 간소화 (10% 포인트 유지)
- 30일 초과: 초고도 간소화 (5% 포인트 유지)
```

#### 2.2.2 간소화 알고리즘
1. **Douglas-Peucker 알고리즘**: 형태 유지하며 포인트 감소
2. **시간 기반 샘플링**: 일정 시간 간격으로 포인트 선택
3. **중요 지점 보존**: 방향 전환, 정지, 속도 변화 지점

### 2.3 구현 계획

#### 2.3.1 TrackSimplificationService
```java
@Service
public class TrackSimplificationService {
    
    public SimplifiedTrack simplifyTrack(
            VesselTrack track, 
            SimplificationLevel level) {
        
        switch (level) {
            case NONE:
                return new SimplifiedTrack(track);
            case LIGHT:
                return applyDouglasPeucker(track, 0.0001); // ~50% reduction
            case MEDIUM:
                return applyDouglasPeucker(track, 0.0005); // ~75% reduction
            case HEAVY:
                return applyDouglasPeucker(track, 0.001); // ~90% reduction
            case EXTREME:
                return applyDouglasPeucker(track, 0.005); // ~95% reduction
        }
    }
    
    private SimplificationLevel determineLevel(LocalDateTime start, LocalDateTime end) {
        long days = ChronoUnit.DAYS.between(start, end);
        
        if (days <= 1) return SimplificationLevel.NONE;
        if (days <= 7) return SimplificationLevel.LIGHT;
        if (days <= 15) return SimplificationLevel.MEDIUM;
        if (days <= 30) return SimplificationLevel.HEAVY;
        return SimplificationLevel.EXTREME;
    }
}
```

#### 2.3.2 적응형 청크 크기 조정
```java
@Component
public class AdaptiveChunkManager {
    
    private static final int MAX_POINTS_PER_CHUNK = 10000;
    private static final int MAX_BYTES_PER_CHUNK = 1_048_576; // 1MB
    
    public ChunkConfiguration calculateOptimalChunking(
            QueryTimeRange range,
            int estimatedVessels) {
        
        SimplificationLevel level = determineSimplificationLevel(range);
        double reductionFactor = level.getReductionFactor();
        
        // 예상 포인트 수 계산
        long totalPoints = estimatedVessels * range.getHours() * 12; // 5분당 1포인트
        long simplifiedPoints = (long)(totalPoints * reductionFactor);
        
        // 최적 청크 크기 계산
        int chunkSize = Math.min(
            MAX_POINTS_PER_CHUNK,
            (int)(simplifiedPoints / 100) // 목표: 100개 청크
        );
        
        return ChunkConfiguration.builder()
            .chunkSize(Math.max(100, chunkSize))
            .simplificationLevel(level)
            .enableCompression(range.getDays() > 7)
            .build();
    }
}
```

#### 2.3.3 WebSocket 메시지 압축
```java
@Configuration
public class WebSocketCompressionConfig {
    
    @Bean
    public CompressionMessagePostProcessor compressionPostProcessor() {
        return new CompressionMessagePostProcessor() {
            @Override
            public boolean shouldCompress(Message<?> message) {
                // 대용량 데이터는 gzip 압축
                return message.getPayload().toString().length() > 10240; // 10KB
            }
        };
    }
}
```

#### 2.3.4 스트리밍 서비스 개선
```java
@Service
public class EnhancedStompTrackStreamingService {
    
    @Autowired
    private TrackSimplificationService simplificationService;
    
    @Autowired
    private AdaptiveChunkManager chunkManager;
    
    public void streamTracks(TrackQueryRequest request, String sessionId) {
        // 청크 설정 계산
        ChunkConfiguration config = chunkManager.calculateOptimalChunking(
            request.getTimeRange(),
            estimateVesselCount(request)
        );
        
        // 간소화 레벨 결정
        SimplificationLevel level = config.getSimplificationLevel();
        
        // 쿼리 실행 시 간소화 적용
        queryExecutor.executeWithSimplification(request, level, (tracks) -> {
            // 간소화된 궤적 전송
            List<SimplifiedTrack> simplified = tracks.stream()
                .map(t -> simplificationService.simplifyTrack(t, level))
                .collect(Collectors.toList());
            
            sendChunk(sessionId, simplified, config);
        });
    }
}
```

### 2.4 PostGIS 활용 간소화
```sql
-- PostGIS ST_Simplify 함수 활용
SELECT 
    sig_src_cd,
    target_id,
    time_bucket,
    ST_Simplify(track_geom, :tolerance) as simplified_geom,
    distance_nm,
    avg_speed
FROM t_vessel_tracks_daily
WHERE time_bucket BETWEEN :start AND :end
    AND ST_NPoints(track_geom) > :min_points;
```

### 2.5 개발 일정
- **Phase 1** (2일): 간소화 알고리즘 구현 및 테스트
- **Phase 2** (3일): 적응형 청크 관리자 구현
- **Phase 3** (2일): WebSocket 압축 설정
- **Phase 4** (3일): 통합 테스트 및 성능 최적화
- **총 소요**: 10일

---

## 3. 통합 아키텍처

```
┌─────────────────┐
│   5분 데이터     │
└────────┬────────┘
         │
    ┌────▼────┐     ┌──────────────────┐
    │ 비정상  │────►│ t_abnormal_tracks │
    │ 검출기  │     └──────────────────┘
    └────┬────┘
         │
    ┌────▼────┐
    │정상 궤적 │
    └────┬────┘
         │
   ┌─────┴─────┐
   │           │
┌──▼───┐  ┌───▼──┐
│1시간  │  │ 1일   │
│집계   │  │ 집계  │
└──────┘  └──────┘
```

## 4. 성능 목표

### 4.1 비정상 궤적 필터링
- 검출 정확도: 95% 이상
- 처리 오버헤드: 10% 이하
- 메모리 사용 증가: 5% 이하

### 4.2 궤적 간소화
- 7일 조회: 10초 이내
- 15일 조회: 20초 이내  
- 30일 조회: 40초 이내
- 시각적 품질 유지: 90% 이상

## 5. 테스트 계획

### 5.1 비정상 궤적 테스트
1. 인위적 비정상 데이터 생성
2. 검출 정확도 측정
3. 성능 영향 평가

### 5.2 간소화 테스트
1. 다양한 시간 범위 조회
2. 간소화 품질 평가
3. 메모리/네트워크 사용량 측정

---
**작성일**: 2025-07-24  
**총 예상 개발 기간**: 23일 (병렬 진행 시 15일)
