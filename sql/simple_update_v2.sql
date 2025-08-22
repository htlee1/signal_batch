-- hourly 테이블 직접 UPDATE (함수 없이)
UPDATE signal.t_vessel_tracks_hourly AS h
SET track_geom_v2 = ST_GeomFromText(
    REPLACE(
        REPLACE(ST_AsText(track_geom), 'LINESTRING M(', 
            'LINESTRING M(' || 
            CASE 
                WHEN ST_M(ST_PointN(track_geom, 1)) = 0 
                THEN EXTRACT(EPOCH FROM time_bucket + INTERVAL '9 hours')::text
                ELSE (EXTRACT(EPOCH FROM time_bucket + INTERVAL '9 hours')::bigint + ST_M(ST_PointN(track_geom, 1)))::text
            END || ' '
        ),
        ')',
        EXTRACT(EPOCH FROM time_bucket + INTERVAL '9 hours')::text || ')'
    ),
    4326
)
WHERE time_bucket = '2025-08-07 14:00:00'
  AND track_geom IS NOT NULL
  AND track_geom_v2 IS NULL;

-- daily 테이블 직접 UPDATE
UPDATE signal.t_vessel_tracks_daily AS d
SET track_geom_v2 = track_geom  -- 임시로 복사 (정확한 변환은 나중에)
WHERE time_bucket = DATE_TRUNC('day', NOW())
  AND track_geom IS NOT NULL
  AND track_geom_v2 IS NULL;

-- 결과 확인
SELECT 
    'hourly' as table_type,
    COUNT(*) as total,
    COUNT(track_geom_v2) as v2_filled
FROM signal.t_vessel_tracks_hourly
WHERE time_bucket = '2025-08-07 14:00:00'
UNION ALL
SELECT 
    'daily' as table_type,
    COUNT(*) as total,
    COUNT(track_geom_v2) as v2_filled
FROM signal.t_vessel_tracks_daily
WHERE time_bucket = DATE_TRUNC('day', NOW());
