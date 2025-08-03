-- ================================================
-- Signal Batch Aggregation System - 실제 테이블 구조
-- PostgreSQL 15+ with PostGIS 3.3+
-- 
-- 목적: 실시간 선박 위치 데이터의 계층적 집계
-- 작성일: 2025-07-30
-- 실제 DB: 10.26.252.48:5432/mdadb (schema: signal)
-- ================================================

-- 스키마 생성
CREATE SCHEMA IF NOT EXISTS signal;

-- PostGIS 확장 활성화
CREATE EXTENSION IF NOT EXISTS postgis;

-- ================================================
-- 1. t_abnormal_track_stats - 비정상 항적 일별 통계
-- ================================================
CREATE TABLE signal.t_abnormal_track_stats (
    stat_date DATE NOT NULL,                           -- 통계 날짜
    abnormal_type VARCHAR(50) NOT NULL,                -- 비정상 유형 (excessive_speed, teleport, impossible_distance, excessive_avg_speed, gap_jump)
    vessel_count INTEGER NOT NULL,                     -- 비정상 항적이 발견된 선박 수
    track_count INTEGER NOT NULL,                      -- 비정상 항적 건수
    total_points INTEGER,                              -- 총 포인트 수
    avg_deviation NUMERIC(10,2),                       -- 평균 편차값 (속도 또는 거리)
    max_deviation NUMERIC(10,2),                       -- 최대 편차값
    created_at TIMESTAMP DEFAULT NOW(),                -- 생성 시각
    updated_at TIMESTAMP DEFAULT NOW(),                -- 수정 시각
    CONSTRAINT t_abnormal_track_stats_pkey PRIMARY KEY (stat_date, abnormal_type)
);

-- 인덱스
CREATE INDEX idx_abnormal_track_stats_date ON signal.t_abnormal_track_stats (stat_date);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_abnormal_track_stats IS '비정상 항적 일별 통계';
COMMENT ON COLUMN signal.t_abnormal_track_stats.stat_date IS '통계 날짜';
COMMENT ON COLUMN signal.t_abnormal_track_stats.abnormal_type IS '비정상 유형 (excessive_speed: 과속, teleport: 순간이동, impossible_distance: 불가능한 거리, excessive_avg_speed: 평균속도 초과, gap_jump: 시간 간격 점프)';
COMMENT ON COLUMN signal.t_abnormal_track_stats.vessel_count IS '비정상 항적이 발견된 선박 수';
COMMENT ON COLUMN signal.t_abnormal_track_stats.track_count IS '비정상 항적 건수';
COMMENT ON COLUMN signal.t_abnormal_track_stats.total_points IS '비정상 항적의 총 포인트 수';
COMMENT ON COLUMN signal.t_abnormal_track_stats.avg_deviation IS '평균 편차값 (속도는 knots, 거리는 nm)';
COMMENT ON COLUMN signal.t_abnormal_track_stats.max_deviation IS '최대 편차값 (속도는 knots, 거리는 nm)';

-- ================================================
-- 2. t_abnormal_tracks - 비정상 선박 항적 저장 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_abnormal_tracks (
    id BIGINT NOT NULL,                                -- ID
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드 (AIS, V-PASS 등)
    target_id VARCHAR(20) NOT NULL,                    -- 타겟 ID (MMSI, 선박번호 등)
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    track_geom GEOMETRY(LINESTRINGM, 4326),          -- 비정상 항적 (M값은 시간)
    abnormal_type VARCHAR(50) NOT NULL,                -- 비정상 유형
    abnormal_reason JSONB NOT NULL,                    -- 비정상 사유 상세
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    max_speed NUMERIC(6,2),                            -- 최대 속도 (knots)
    point_count INTEGER,                               -- 항적 포인트 수
    source_table VARCHAR(50) NOT NULL,                 -- 원본 테이블명 (5min/hourly/daily)
    detected_at TIMESTAMP DEFAULT NOW(),               -- 검출 시각
    CONSTRAINT t_abnormal_tracks_pkey PRIMARY KEY (id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE UNIQUE INDEX abnormal_tracks_uk ON signal.t_abnormal_tracks (sig_src_cd, target_id, time_bucket, source_table);
CREATE INDEX idx_abnormal_tracks_vessel ON signal.t_abnormal_tracks (sig_src_cd, target_id);
CREATE INDEX idx_abnormal_tracks_time ON signal.t_abnormal_tracks (time_bucket);
CREATE INDEX idx_abnormal_tracks_type ON signal.t_abnormal_tracks (abnormal_type);
CREATE INDEX idx_abnormal_tracks_geom ON signal.t_abnormal_tracks USING GIST (track_geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_abnormal_tracks IS '비정상 선박 항적 저장 테이블';
COMMENT ON COLUMN signal.t_abnormal_tracks.sig_src_cd IS '신호원 코드 (AIS, V-PASS, E-Navigation 등)';
COMMENT ON COLUMN signal.t_abnormal_tracks.target_id IS '타겟 ID (MMSI, V-PASS ID, 선박번호 등)';
COMMENT ON COLUMN signal.t_abnormal_tracks.time_bucket IS '5분 단위 시간 버킷';
COMMENT ON COLUMN signal.t_abnormal_tracks.track_geom IS 'LineStringM 형식 항적 (M값은 첫 포인트 기준 상대시간)';
COMMENT ON COLUMN signal.t_abnormal_tracks.abnormal_type IS '비정상 유형';
COMMENT ON COLUMN signal.t_abnormal_tracks.abnormal_reason IS '비정상 사유 상세 정보 JSON';
COMMENT ON COLUMN signal.t_abnormal_tracks.distance_nm IS '총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_abnormal_tracks.avg_speed IS '평균 속도 (knots)';
COMMENT ON COLUMN signal.t_abnormal_tracks.max_speed IS '최대 속도 (knots)';
COMMENT ON COLUMN signal.t_abnormal_tracks.point_count IS '항적을 구성하는 포인트 수';
COMMENT ON COLUMN signal.t_abnormal_tracks.source_table IS '검출된 원본 테이블 (t_vessel_tracks_5min 등)';

-- ================================================
-- 3. t_area_statistics - 사용자 정의 영역별 선박 통계 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_area_statistics (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    vessel_count INTEGER DEFAULT 0,                    -- 선박 수
    in_count INTEGER DEFAULT 0,                        -- 진입 선박 수
    out_count INTEGER DEFAULT 0,                       -- 이탈 선박 수
    transit_vessels JSONB,                             -- 통과 선박 목록
    stationary_vessels JSONB,                          -- 정박 선박 목록
    avg_sog NUMERIC(25,1),                            -- 평균 대지속력
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_area_statistics_pkey PRIMARY KEY (area_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_area_stats_lookup ON signal.t_area_statistics (area_id, time_bucket DESC);
CREATE INDEX idx_area_stats_congestion ON signal.t_area_statistics (vessel_count DESC);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_area_statistics IS '사용자 정의 영역별 5분 단위 선박 통계';
COMMENT ON COLUMN signal.t_area_statistics.area_id IS '영역 ID (t_areas 테이블 참조)';
COMMENT ON COLUMN signal.t_area_statistics.time_bucket IS '5분 단위 시간 버킷';
COMMENT ON COLUMN signal.t_area_statistics.vessel_count IS '해당 시간에 영역 내 선박 수';
COMMENT ON COLUMN signal.t_area_statistics.in_count IS '해당 시간에 영역에 진입한 선박 수';
COMMENT ON COLUMN signal.t_area_statistics.out_count IS '해당 시간에 영역에서 이탈한 선박 수';
COMMENT ON COLUMN signal.t_area_statistics.transit_vessels IS '통과 선박 목록 JSON 배열';
COMMENT ON COLUMN signal.t_area_statistics.stationary_vessels IS '정박 선박 목록 JSON 배열';
COMMENT ON COLUMN signal.t_area_statistics.avg_sog IS '평균 대지속력 (knots)';

-- ================================================
-- 4. t_area_tracks_summary - 영역별 항적 요약 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_area_tracks_summary (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    metrics_summary JSONB,                             -- 메트릭 요약
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_area_tracks_summary_pkey PRIMARY KEY (area_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_area_tracks_summary IS '영역별 5분 단위 항적 요약 통계';
COMMENT ON COLUMN signal.t_area_tracks_summary.area_id IS '영역 ID';
COMMENT ON COLUMN signal.t_area_tracks_summary.time_bucket IS '5분 단위 시간 버킷';
COMMENT ON COLUMN signal.t_area_tracks_summary.total_vessels IS '영역 내 총 선박 수';
COMMENT ON COLUMN signal.t_area_tracks_summary.total_distance_nm IS '모든 선박의 총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_area_tracks_summary.avg_speed IS '모든 선박의 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_area_tracks_summary.vessel_list IS '선박별 상세 정보 {sig_src_cd, target_id, distance_nm, avg_speed}';
COMMENT ON COLUMN signal.t_area_tracks_summary.metrics_summary IS '추가 메트릭 정보';

-- ================================================
-- 5. t_area_tracks_summary_daily - 영역별 일일 항적 요약 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_area_tracks_summary_daily (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    time_bucket DATE NOT NULL,                         -- 날짜 (일 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    metrics_summary JSONB,                             -- 메트릭 요약
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_area_tracks_summary_daily_pkey PRIMARY KEY (area_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_area_tracks_daily_time ON signal.t_area_tracks_summary_daily (time_bucket);
CREATE INDEX idx_area_tracks_daily_area ON signal.t_area_tracks_summary_daily (area_id);
CREATE INDEX idx_area_tracks_summary_daily_time_area ON signal.t_area_tracks_summary_daily (time_bucket DESC, area_id);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_area_tracks_summary_daily IS '영역별 일일 항적 요약 통계';
COMMENT ON COLUMN signal.t_area_tracks_summary_daily.time_bucket IS '일 단위 날짜';
COMMENT ON COLUMN signal.t_area_tracks_summary_daily.total_vessels IS '해당일 영역을 방문한 고유 선박 수';

-- ================================================
-- 6. t_area_tracks_summary_hourly - 영역별 시간별 항적 요약 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_area_tracks_summary_hourly (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (1시간 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    metrics_summary JSONB,                             -- 메트릭 요약
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_area_tracks_summary_hourly_pkey PRIMARY KEY (area_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_area_tracks_summary_hourly_time_area ON signal.t_area_tracks_summary_hourly (time_bucket DESC, area_id);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_area_tracks_summary_hourly IS '영역별 시간별 항적 요약 통계';
COMMENT ON COLUMN signal.t_area_tracks_summary_hourly.time_bucket IS '1시간 단위 시간 버킷';

-- ================================================
-- 7. t_area_vessel_tracks - 영역별 선박 항적 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_area_vessel_tracks (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    metrics JSONB,                                     -- 추가 메트릭
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_area_vessel_tracks_pkey PRIMARY KEY (area_id, sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_area_vessel_tracks_vessel_time ON signal.t_area_vessel_tracks (sig_src_cd, target_id, time_bucket DESC);
CREATE INDEX idx_area_vessel_tracks_area_time_desc ON signal.t_area_vessel_tracks (area_id, time_bucket DESC);
CREATE INDEX idx_area_vessel_tracks_area_vessel_time ON signal.t_area_vessel_tracks (area_id, sig_src_cd, target_id, time_bucket DESC);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_area_vessel_tracks IS '영역별 선박 항적 (5분 단위)';
COMMENT ON COLUMN signal.t_area_vessel_tracks.area_id IS '영역 ID';
COMMENT ON COLUMN signal.t_area_vessel_tracks.distance_nm IS '영역 내 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_area_vessel_tracks.avg_speed IS '영역 내 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_area_vessel_tracks.point_count IS '영역 내 포인트 수';
COMMENT ON COLUMN signal.t_area_vessel_tracks.metrics IS '추가 메트릭 정보 (max_speed, entry/exit_time 등)';

-- ================================================
-- 8. t_areas - 사용자 정의 영역
-- ================================================
CREATE TABLE signal.t_areas (
    area_id VARCHAR(50) NOT NULL,                      -- 영역 ID
    area_name VARCHAR(100) NOT NULL,                   -- 영역명
    area_type VARCHAR(20) NOT NULL,                    -- 영역 유형 (port, anchorage, fishing 등)
    area_geom GEOMETRY(MULTIPOLYGON, 4326) NOT NULL,   -- 영역 경계
    properties JSONB,                                  -- 추가 속성
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_areas_pkey PRIMARY KEY (area_id)
);

-- 인덱스
CREATE INDEX idx_t_areas_area_geom ON signal.t_areas USING GIST (area_geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_areas IS '사용자 정의 영역 정보';
COMMENT ON COLUMN signal.t_areas.area_id IS '영역 고유 ID';
COMMENT ON COLUMN signal.t_areas.area_name IS '영역 이름';
COMMENT ON COLUMN signal.t_areas.area_type IS '영역 유형 (항구, 정박지, 어장 등)';
COMMENT ON COLUMN signal.t_areas.area_geom IS '영역 경계 (MultiPolygon)';
COMMENT ON COLUMN signal.t_areas.properties IS '추가 속성 정보';

-- ================================================
-- 9. t_batch_performance_metrics - 배치 작업 성능 메트릭
-- ================================================
CREATE TABLE signal.t_batch_performance_metrics (
    id SERIAL PRIMARY KEY,                             -- 자동 증가 ID
    job_name VARCHAR(100) NOT NULL,                    -- Job 이름
    execution_id BIGINT NOT NULL,                      -- 실행 ID
    start_time TIMESTAMP NOT NULL,                     -- 시작 시각
    end_time TIMESTAMP,                                -- 종료 시각
    duration_seconds BIGINT,                           -- 실행 시간 (초)
    total_read BIGINT,                                 -- 읽은 레코드 수
    total_write BIGINT,                                -- 쓴 레코드 수
    throughput_per_sec NUMERIC(10,2),                  -- 초당 처리량
    status VARCHAR(20),                                -- 상태 (STARTED, COMPLETED, FAILED)
    error_message TEXT,                                -- 에러 메시지
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP     -- 생성 시각
);

-- 인덱스
CREATE INDEX idx_batch_metrics_job ON signal.t_batch_performance_metrics (job_name, start_time DESC);
CREATE INDEX idx_batch_metrics_status ON signal.t_batch_performance_metrics (status) WHERE status != 'COMPLETED';

-- 테이블 코멘트
COMMENT ON TABLE signal.t_batch_performance_metrics IS '배치 작업 성능 메트릭';
COMMENT ON COLUMN signal.t_batch_performance_metrics.job_name IS 'Spring Batch Job 이름';
COMMENT ON COLUMN signal.t_batch_performance_metrics.execution_id IS 'Spring Batch 실행 ID';
COMMENT ON COLUMN signal.t_batch_performance_metrics.duration_seconds IS '실행 소요 시간 (초)';
COMMENT ON COLUMN signal.t_batch_performance_metrics.throughput_per_sec IS '초당 처리 레코드 수';
COMMENT ON COLUMN signal.t_batch_performance_metrics.status IS '실행 상태';

-- ================================================
-- 10. t_grid_tiles - 그리드 타일 정의 (대해구/소해구)
-- ================================================
CREATE TABLE signal.t_grid_tiles (
    tile_id VARCHAR(50) NOT NULL,                      -- 타일 ID
    tile_level INTEGER NOT NULL,                       -- 타일 레벨 (1: 대해구, 2: 소해구)
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    sohaegu_no INTEGER,                                -- 소해구 번호
    min_lat DOUBLE PRECISION NOT NULL,                 -- 최소 위도
    min_lon DOUBLE PRECISION NOT NULL,                 -- 최소 경도
    max_lat DOUBLE PRECISION NOT NULL,                 -- 최대 위도
    max_lon DOUBLE PRECISION NOT NULL,                 -- 최대 경도
    tile_geom GEOMETRY(POLYGON, 4326) NOT NULL,        -- 타일 경계
    center_point GEOMETRY(POINT, 4326) NOT NULL,       -- 중심점
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_tiles_pkey PRIMARY KEY (tile_id)
);

-- 인덱스
CREATE INDEX idx_grid_tiles_tile_geom ON signal.t_grid_tiles USING GIST (tile_geom);
CREATE INDEX idx_grid_tiles_haegu ON signal.t_grid_tiles (haegu_no);
CREATE INDEX idx_grid_tiles_sohaegu ON signal.t_grid_tiles (sohaegu_no) WHERE sohaegu_no IS NOT NULL;
CREATE INDEX idx_grid_tiles_level ON signal.t_grid_tiles (tile_level);
CREATE INDEX idx_grid_tiles_haegu_sohaegu ON signal.t_grid_tiles (haegu_no, sohaegu_no);
CREATE INDEX idx_grid_tiles_tile_level ON signal.t_grid_tiles (tile_id, tile_level);
CREATE INDEX idx_grid_tiles_tile_id ON signal.t_grid_tiles (tile_id);
CREATE INDEX idx_grid_tiles_geom ON signal.t_grid_tiles USING GIST (tile_geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_tiles IS '그리드 타일 정의 (대해구/소해구)';
COMMENT ON COLUMN signal.t_grid_tiles.tile_id IS '타일 고유 ID';
COMMENT ON COLUMN signal.t_grid_tiles.tile_level IS '타일 레벨 (1: 대해구, 2: 소해구)';
COMMENT ON COLUMN signal.t_grid_tiles.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_tiles.sohaegu_no IS '소해구 번호 (소해구인 경우)';
COMMENT ON COLUMN signal.t_grid_tiles.min_lat IS '타일 최소 위도';
COMMENT ON COLUMN signal.t_grid_tiles.min_lon IS '타일 최소 경도';
COMMENT ON COLUMN signal.t_grid_tiles.max_lat IS '타일 최대 위도';
COMMENT ON COLUMN signal.t_grid_tiles.max_lon IS '타일 최대 경도';
COMMENT ON COLUMN signal.t_grid_tiles.tile_geom IS '타일 경계 폴리곤';
COMMENT ON COLUMN signal.t_grid_tiles.center_point IS '타일 중심점';

-- ================================================
-- 11. t_grid_tracks_summary - 해구별 항적 요약 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_grid_tracks_summary (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    traffic_density NUMERIC(10,4),                     -- 교통 밀도
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_tracks_summary_pkey PRIMARY KEY (haegu_no, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_tracks_summary IS '해구별 5분 단위 항적 요약 통계';
COMMENT ON COLUMN signal.t_grid_tracks_summary.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_tracks_summary.time_bucket IS '5분 단위 시간 버킷';
COMMENT ON COLUMN signal.t_grid_tracks_summary.total_vessels IS '해구 내 총 선박 수';
COMMENT ON COLUMN signal.t_grid_tracks_summary.total_distance_nm IS '모든 선박의 총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_grid_tracks_summary.avg_speed IS '모든 선박의 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_grid_tracks_summary.vessel_list IS '선박별 상세 정보 {sig_src_cd, target_id, distance_nm, avg_speed}';
COMMENT ON COLUMN signal.t_grid_tracks_summary.traffic_density IS '교통 밀도 (선박수/면적)';


-- ================================================
-- 12. t_grid_tracks_summary_daily - 해구별 일일 항적 요약 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_grid_tracks_summary_daily (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    time_bucket DATE NOT NULL,                         -- 날짜 (일 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_tracks_summary_daily_pkey PRIMARY KEY (haegu_no, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_grid_tracks_daily_time ON signal.t_grid_tracks_summary_daily (time_bucket);
CREATE INDEX idx_grid_tracks_daily_haegu ON signal.t_grid_tracks_summary_daily (haegu_no);
CREATE INDEX idx_grid_tracks_summary_daily_time_haegu ON signal.t_grid_tracks_summary_daily (time_bucket DESC, haegu_no);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_tracks_summary_daily IS '해구별 일일 항적 요약 통계';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.time_bucket IS '1시간 단위 시간 버킷';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.total_vessels IS '해구 내 총 선박 수';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.total_distance_nm IS '모든 선박의 총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.avg_speed IS '모든 선박의 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_grid_tracks_summary_daily.vessel_list IS '선박별 상세 정보 {sig_src_cd, target_id, distance_nm, avg_speed}';

-- ================================================
-- 13. t_grid_tracks_summary_hourly - 해구별 시간별 항적 요약 (파티션 테이블)
-- ================================================
CREATE TABLE signal.t_grid_tracks_summary_hourly (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (1시간 단위)
    total_vessels INTEGER,                             -- 총 선박 수
    total_distance_nm NUMERIC(12,2),                   -- 총 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    vessel_list JSONB,                                 -- 선박 목록
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_tracks_summary_hourly_pkey PRIMARY KEY (haegu_no, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_grid_tracks_hourly_time ON signal.t_grid_tracks_summary_hourly (time_bucket);
CREATE INDEX idx_grid_tracks_hourly_haegu ON signal.t_grid_tracks_summary_hourly (haegu_no);
CREATE INDEX idx_grid_tracks_summary_hourly_time_haegu ON signal.t_grid_tracks_summary_hourly (time_bucket DESC, haegu_no);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_tracks_summary_hourly IS '해구별 시간별 항적 요약 통계';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.time_bucket IS '1시간 단위 시간 버킷';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.total_vessels IS '해구 내 총 선박 수';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.total_distance_nm IS '모든 선박의 총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.avg_speed IS '모든 선박의 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_grid_tracks_summary_hourly.vessel_list IS '선박별 상세 정보 {sig_src_cd, target_id, distance_nm, avg_speed}';

-- ================================================
-- 14. t_grid_vessel_tracks - 해구별 선박 항적 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_grid_vessel_tracks (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    entry_time TIMESTAMP,                              -- 해구 진입 시각
    exit_time TIMESTAMP,                               -- 해구 이탈 시각
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_vessel_tracks_pkey PRIMARY KEY (haegu_no, sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_grid_vessel_tracks_vessel_time ON signal.t_grid_vessel_tracks (sig_src_cd, target_id, time_bucket DESC);
CREATE INDEX idx_grid_vessel_tracks_haegu_time_desc ON signal.t_grid_vessel_tracks (haegu_no, time_bucket DESC);
CREATE INDEX idx_grid_vessel_tracks_entry_exit ON signal.t_grid_vessel_tracks (entry_time, exit_time) WHERE entry_time IS NOT NULL;

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_vessel_tracks IS '해구별 선박 항적 (5분 단위)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.distance_nm IS '해구 내 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.avg_speed IS '해구 내 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.point_count IS '해구 내 포인트 수';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.entry_time IS '해구 진입 시각';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.exit_time IS '해구 이탈 시각';
COMMENT ON COLUMN signal.t_grid_vessel_tracks.created_at IS '생성 시각';

-- ================================================
-- 15. t_grid_vessel_tracks_hourly - 해구별 선박 항적 (시간별, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_grid_vessel_tracks_hourly (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (1시간 단위)
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    entry_time TIMESTAMP,                              -- 해구 진입 시각
    exit_time TIMESTAMP,                               -- 해구 이탈 시각
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_grid_vessel_tracks_hourly_pkey PRIMARY KEY (haegu_no, sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_grid_vessel_tracks_hourly IS '해구별 선박 항적 (시간별)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.distance_nm IS '해구 내 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.avg_speed IS '해구 내 평균 속도 (knots)';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.point_count IS '해구 내 포인트 수';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.entry_time IS '해구 진입 시각';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.exit_time IS '해구 이탈 시각';
COMMENT ON COLUMN signal.t_grid_vessel_tracks_hourly.created_at IS '생성 시각';

-- ================================================
-- 16. t_haegu_definitions - 대해구 정의
-- ================================================
CREATE TABLE signal.t_haegu_definitions (
    haegu_no INTEGER NOT NULL,                         -- 대해구 번호
    min_lat DOUBLE PRECISION NOT NULL,                 -- 최소 위도
    min_lon DOUBLE PRECISION NOT NULL,                 -- 최소 경도
    max_lat DOUBLE PRECISION NOT NULL,                 -- 최대 위도
    max_lon DOUBLE PRECISION NOT NULL,                 -- 최대 경도
    center_lat DOUBLE PRECISION NOT NULL,              -- 중심 위도
    center_lon DOUBLE PRECISION NOT NULL,              -- 중심 경도
    geom GEOMETRY(MULTIPOLYGON, 4326) NOT NULL,       -- 대해구 경계
    center_point GEOMETRY(POINT, 4326) NOT NULL,       -- 중심점
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_haegu_definitions_pkey PRIMARY KEY (haegu_no)
);

-- 인덱스
CREATE INDEX idx_haegu_definitions_geom ON signal.t_haegu_definitions USING GIST (geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_haegu_definitions IS '대해구 정의 정보';
COMMENT ON COLUMN signal.t_haegu_definitions.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_haegu_definitions.min_lat IS '대해구 최소 위도';
COMMENT ON COLUMN signal.t_haegu_definitions.min_lon IS '대해구 최소 경도';
COMMENT ON COLUMN signal.t_haegu_definitions.max_lat IS '대해구 최대 위도';
COMMENT ON COLUMN signal.t_haegu_definitions.max_lon IS '대해구 최대 경도';
COMMENT ON COLUMN signal.t_haegu_definitions.center_lat IS '대해구 중심 위도';
COMMENT ON COLUMN signal.t_haegu_definitions.center_lon IS '대해구 중심 경도';
COMMENT ON COLUMN signal.t_haegu_definitions.geom IS '대해구 경계 (MultiPolygon)';
COMMENT ON COLUMN signal.t_haegu_definitions.center_point IS '대해구 중심점';

-- ================================================
-- 17. t_tile_summary - 타일별 선박 요약 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_tile_summary (
    tile_id VARCHAR(50) NOT NULL,                      -- 타일 ID
    tile_level INTEGER NOT NULL,                       -- 타일 레벨
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    vessel_count INTEGER DEFAULT 0,                    -- 선박 수
    unique_vessels JSONB,                              -- 고유 선박 목록
    total_points BIGINT DEFAULT 0,                     -- 총 포인트 수
    avg_sog NUMERIC(25,1),                            -- 평균 대지속력
    max_sog NUMERIC(25,1),                            -- 최대 대지속력
    vessel_density NUMERIC(10,6),                      -- 선박 밀도
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    haegu_no INTEGER,                                  -- 대해구 번호
    sohaegu_no INTEGER,                                -- 소해구 번호
    CONSTRAINT t_tile_summary_pkey PRIMARY KEY (tile_id, time_bucket, tile_level)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_tile_summary_lookup ON signal.t_tile_summary (tile_id, time_bucket DESC);
CREATE INDEX idx_tile_summary_time ON signal.t_tile_summary (time_bucket DESC);
CREATE INDEX idx_tile_summary_vessel_count ON signal.t_tile_summary (vessel_count DESC);
CREATE INDEX idx_tile_summary_density ON signal.t_tile_summary (vessel_density) WHERE vessel_density > 0;
CREATE INDEX idx_tile_summary_tile_level ON signal.t_tile_summary (tile_level);
CREATE INDEX idx_tile_summary_bucket_tile_level ON signal.t_tile_summary (time_bucket, tile_id, tile_level);
CREATE INDEX idx_tile_summary_time_bucket ON signal.t_tile_summary (time_bucket DESC);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_tile_summary IS '타일별 5분 단위 선박 요약 통계';
COMMENT ON COLUMN signal.t_tile_summary.tile_id IS '타일 ID';
COMMENT ON COLUMN signal.t_tile_summary.tile_level IS '타일 레벨 (1: 대해구, 2: 소해구)';
COMMENT ON COLUMN signal.t_tile_summary.vessel_count IS '타일 내 선박 수';
COMMENT ON COLUMN signal.t_tile_summary.unique_vessels IS '고유 선박 목록 [{sig_src_cd, target_id}]';
COMMENT ON COLUMN signal.t_tile_summary.total_points IS '총 위치 포인트 수';
COMMENT ON COLUMN signal.t_tile_summary.avg_sog IS '평균 대지속력 (knots)';
COMMENT ON COLUMN signal.t_tile_summary.max_sog IS '최대 대지속력 (knots)';
COMMENT ON COLUMN signal.t_tile_summary.vessel_density IS '선박 밀도 (선박수/평방킬로미터)';
COMMENT ON COLUMN signal.t_tile_summary.haegu_no IS '대해구 번호';
COMMENT ON COLUMN signal.t_tile_summary.sohaegu_no IS '소해구 번호';

-- ================================================
-- 18. t_vessel_latest_position - 선박 최신 위치
-- ================================================
CREATE TABLE signal.t_vessel_latest_position (
    sig_src_cd VARCHAR(6) NOT NULL,                   -- 신호원 코드
    target_id VARCHAR(20) NOT NULL,                    -- 타겟 ID
    lat DOUBLE PRECISION NOT NULL,                     -- 위도
    lon DOUBLE PRECISION NOT NULL,                     -- 경도
    geom GEOMETRY(POINT, 4326),                       -- 위치 (PostGIS)
    sog NUMERIC(25,1),                                -- 대지속력 (knots)
    cog NUMERIC(25,1),                                -- 대지침로 (도)
    heading INTEGER,                                   -- 선수방향 (도)
    ship_nm VARCHAR(30),                              -- 선박명
    ship_ty VARCHAR(25),                              -- 선박 유형
    last_update TIMESTAMP NOT NULL,                    -- 최종 업데이트 시각
    update_count BIGINT DEFAULT 1,                     -- 업데이트 횟수
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_vessel_latest_position_pkey PRIMARY KEY (sig_src_cd, target_id)
);

-- 인덱스
CREATE INDEX idx_vessel_latest_position_geom ON signal.t_vessel_latest_position USING GIST (geom);
CREATE INDEX idx_latest_position_geom ON signal.t_vessel_latest_position USING GIST (geom);
CREATE INDEX idx_latest_position_update ON signal.t_vessel_latest_position (last_update DESC);
CREATE INDEX idx_latest_position_ship_type ON signal.t_vessel_latest_position (ship_ty);
CREATE INDEX idx_vessel_latest_position_key ON signal.t_vessel_latest_position ((sig_src_cd || ':' || target_id));

-- 테이블 코멘트
COMMENT ON TABLE signal.t_vessel_latest_position IS '선박 최신 위치 정보';
COMMENT ON COLUMN signal.t_vessel_latest_position.sig_src_cd IS '신호원 코드';
COMMENT ON COLUMN signal.t_vessel_latest_position.target_id IS '타겟 ID';
COMMENT ON COLUMN signal.t_vessel_latest_position.lat IS '위도';
COMMENT ON COLUMN signal.t_vessel_latest_position.lon IS '경도';
COMMENT ON COLUMN signal.t_vessel_latest_position.geom IS 'PostGIS Point 형식 위치';
COMMENT ON COLUMN signal.t_vessel_latest_position.sog IS '대지속력 (Speed Over Ground, knots)';
COMMENT ON COLUMN signal.t_vessel_latest_position.cog IS '대지침로 (Course Over Ground, 도)';
COMMENT ON COLUMN signal.t_vessel_latest_position.heading IS '선수방향 (Heading, 도)';
COMMENT ON COLUMN signal.t_vessel_latest_position.ship_nm IS '선박명';
COMMENT ON COLUMN signal.t_vessel_latest_position.ship_ty IS '선박 유형';
COMMENT ON COLUMN signal.t_vessel_latest_position.last_update IS '최종 업데이트 시각';
COMMENT ON COLUMN signal.t_vessel_latest_position.update_count IS '위치 업데이트 횟수';

-- ================================================
-- 19. t_vessel_tracks_5min - 선박 항적 (5분, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_vessel_tracks_5min (
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (5분 단위)
    track_geom GEOMETRY(LINESTRINGM, 4326),          -- 항적
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    max_speed NUMERIC(6,2),                            -- 최대 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    start_position JSONB,                              -- 시작 위치
    end_position JSONB,                                -- 종료 위치
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_vessel_tracks_5min_pkey PRIMARY KEY (sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_vessel_tracks_5min IS '선박 항적 5분 단위 집계';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.sig_src_cd IS '신호원 코드';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.target_id IS '타겟 ID';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.time_bucket IS '5분 단위 시간 버킷';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.track_geom IS 'LineStringM 형식 항적 (M값은 첫 포인트 기준 상대시간)';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.distance_nm IS '총 이동 거리 (해리)';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.avg_speed IS '평균 속도 (knots, ST_Length 기반 계산)';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.max_speed IS '최대 속도 (knots)';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.point_count IS '항적을 구성하는 포인트 수';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.start_position IS '시작 위치 JSON {lat, lon, time, sog}';
COMMENT ON COLUMN signal.t_vessel_tracks_5min.end_position IS '종료 위치 JSON {lat, lon, time, sog}';

-- ================================================
-- 20. t_vessel_tracks_daily - 선박 항적 (일별, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_vessel_tracks_daily (
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket DATE NOT NULL,                         -- 날짜 (일 단위)
    track_geom GEOMETRY(LINESTRINGM, 4326),          -- 항적
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    max_speed NUMERIC(6,2),                            -- 최대 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    operating_hours NUMERIC(4,2),                      -- 운항 시간
    port_visits JSONB,                                 -- 항구 방문 정보
    start_position JSONB,                              -- 시작 위치
    end_position JSONB,                                -- 종료 위치
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_vessel_tracks_daily_pkey PRIMARY KEY (sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_vessel_tracks_daily_vessel ON signal.t_vessel_tracks_daily (sig_src_cd, target_id);
CREATE INDEX idx_vessel_tracks_daily_time ON signal.t_vessel_tracks_daily (time_bucket);
CREATE INDEX idx_vessel_tracks_daily_track_geom ON signal.t_vessel_tracks_daily USING GIST (track_geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_vessel_tracks_daily IS '선박 항적 일별 집계';
COMMENT ON COLUMN signal.t_vessel_tracks_daily.time_bucket IS '일 단위 날짜';
COMMENT ON COLUMN signal.t_vessel_tracks_daily.track_geom IS '일별 병합된 항적 (간소화 적용)';
COMMENT ON COLUMN signal.t_vessel_tracks_daily.operating_hours IS '실제 운항 시간';
COMMENT ON COLUMN signal.t_vessel_tracks_daily.port_visits IS '항구 방문 정보 [{port_id, entry_time, exit_time}]';

-- ================================================
-- 21. t_vessel_tracks_hourly - 선박 항적 (시간별, 파티션 테이블)
-- ================================================
CREATE TABLE signal.t_vessel_tracks_hourly (
    sig_src_cd VARCHAR(10) NOT NULL,                  -- 신호원 코드
    target_id VARCHAR(50) NOT NULL,                    -- 타겟 ID
    time_bucket TIMESTAMP NOT NULL,                    -- 시간 버킷 (1시간 단위)
    track_geom GEOMETRY(LINESTRINGM, 4326),          -- 항적
    distance_nm NUMERIC(10,2),                         -- 이동 거리 (해리)
    avg_speed NUMERIC(6,2),                            -- 평균 속도 (knots)
    max_speed NUMERIC(6,2),                            -- 최대 속도 (knots)
    point_count INTEGER,                               -- 포인트 수
    start_position JSONB,                              -- 시작 위치
    end_position JSONB,                                -- 종료 위치
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 생성 시각
    CONSTRAINT t_vessel_tracks_hourly_pkey PRIMARY KEY (sig_src_cd, target_id, time_bucket)
) PARTITION BY RANGE (time_bucket);

-- 인덱스
CREATE INDEX idx_vessel_tracks_hourly_vessel ON signal.t_vessel_tracks_hourly (sig_src_cd, target_id);
CREATE INDEX idx_vessel_tracks_hourly_time ON signal.t_vessel_tracks_hourly (time_bucket);
CREATE INDEX idx_vessel_tracks_hourly_track_geom ON signal.t_vessel_tracks_hourly USING GIST (track_geom);

-- 테이블 코멘트
COMMENT ON TABLE signal.t_vessel_tracks_hourly IS '선박 항적 시간별 집계';
COMMENT ON COLUMN signal.t_vessel_tracks_hourly.time_bucket IS '1시간 단위 시간 버킷';
COMMENT ON COLUMN signal.t_vessel_tracks_hourly.track_geom IS '시간별 병합된 항적 (간소화 적용)';
