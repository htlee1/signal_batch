-- Unix timestamp 변환 함수
CREATE OR REPLACE FUNCTION signal.convert_to_unix_timestamp(
    geom geometry,
    base_time timestamp without time zone
) RETURNS geometry AS $$
DECLARE
    wkt_text text;
    points text[];
    point_text text;
    coords text[];
    result_wkt text;
    unix_base bigint;
    relative_seconds bigint;
    unix_time bigint;
    i integer;
BEGIN
    IF geom IS NULL THEN 
        RETURN NULL; 
    END IF;
    
    -- Unix timestamp 기준값
    unix_base := EXTRACT(EPOCH FROM base_time AT TIME ZONE 'Asia/Seoul')::bigint;
    
    -- WKT 텍스트 추출
    wkt_text := ST_AsText(geom);
    
    -- LINESTRING M(...) 에서 좌표 부분만 추출
    wkt_text := substring(wkt_text from 'LINESTRING M\((.*)\)');
    
    -- 각 포인트를 배열로 분리
    points := string_to_array(wkt_text, ', ');
    
    -- 결과 WKT 시작
    result_wkt := 'LINESTRING M(';
    
    -- 각 포인트 처리
    FOR i IN 1..array_length(points, 1) LOOP
        -- 좌표를 공백으로 분리 (lon lat m)
        coords := string_to_array(points[i], ' ');
        
        -- M값(상대시간 초) 추출 및 Unix timestamp로 변환
        relative_seconds := coords[3]::bigint;
        unix_time := unix_base + relative_seconds;
        
        -- 결과에 추가
        IF i > 1 THEN 
            result_wkt := result_wkt || ', ';
        END IF;
        result_wkt := result_wkt || coords[1] || ' ' || coords[2] || ' ' || unix_time;
    END LOOP;
    
    result_wkt := result_wkt || ')';
    
    -- geometry 타입으로 변환하여 반환
    RETURN ST_GeomFromText(result_wkt, 4326);
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- 함수 테스트
SELECT 
    sig_src_cd,
    target_id,
    time_bucket,
    ST_AsText(track_geom) as original,
    ST_AsText(signal.convert_to_unix_timestamp(track_geom, time_bucket)) as converted
FROM signal.t_vessel_tracks_5min
WHERE track_geom IS NOT NULL
LIMIT 1;
