-- Unix timestamp 변환을 위한 간단한 UPDATE 쿼리
-- 5분 집계 테이블
UPDATE signal.t_vessel_tracks_5min
SET track_geom_v2 = signal.convert_to_unix_timestamp(track_geom, time_bucket)
WHERE time_bucket >= NOW() - INTERVAL '2 hours'
  AND track_geom IS NOT NULL
  AND track_geom_v2 IS NULL;

-- 1시간 집계 테이블 (오후 2시 데이터)
UPDATE signal.t_vessel_tracks_hourly
SET track_geom_v2 = signal.convert_to_unix_timestamp(track_geom, time_bucket)
WHERE time_bucket = '2025-08-07 14:00:00'
  AND track_geom IS NOT NULL
  AND track_geom_v2 IS NULL;

-- 일별 집계 테이블 (오늘 데이터)
UPDATE signal.t_vessel_tracks_daily
SET track_geom_v2 = signal.convert_to_unix_timestamp(track_geom, time_bucket)
WHERE time_bucket = DATE_TRUNC('day', NOW())
  AND track_geom IS NOT NULL
  AND track_geom_v2 IS NULL;

-- 결과 확인
SELECT 
    'hourly' as table_type,
    COUNT(*) as total_records,
    COUNT(track_geom) as v1_count,
    COUNT(track_geom_v2) as v2_count
FROM signal.t_vessel_tracks_hourly
WHERE time_bucket = '2025-08-07 14:00:00'

UNION ALL

SELECT 
    'daily' as table_type,
    COUNT(*) as total_records,
    COUNT(track_geom) as v1_count,
    COUNT(track_geom_v2) as v2_count
FROM signal.t_vessel_tracks_daily
WHERE time_bucket = DATE_TRUNC('day', NOW());
