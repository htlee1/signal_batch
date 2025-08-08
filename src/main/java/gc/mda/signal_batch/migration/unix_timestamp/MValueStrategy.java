package gc.mda.signal_batch.migration.unix_timestamp;

/**
 * MIGRATION_V2: M값 처리 전략 인터페이스
 * Unix timestamp 전환 완료 후 제거 예정
 */
public interface MValueStrategy {
    /**
     * LineStringM 생성
     */
    String buildLineStringM(java.util.List<gc.mda.signal_batch.model.VesselTrack.TrackPoint> points);
    
    /**
     * M값 추출
     */
    long extractMValue(String wkt, int pointIndex);
    
    /**
     * 시간 변환
     */
    java.time.LocalDateTime convertToDateTime(long mValue, java.time.LocalDateTime baseTime);
}
