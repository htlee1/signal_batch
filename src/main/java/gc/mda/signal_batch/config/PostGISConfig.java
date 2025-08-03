package gc.mda.signal_batch.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import org.springframework.context.annotation.Configuration;

@Configuration
public class PostGISConfig {

    /**
     * PostGIS 관련 설정
     */
    public static void registerPostGISTypes(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // search_path 설정 - signal 스키마와 public 스키마 모두 포함
            stmt.execute("SET search_path TO signal, public");
            
            // bytea 출력 형식 설정 (geometry 타입 처리를 위해)
            stmt.execute("SET bytea_output = 'hex'");
            
            // PostGIS 함수들이 사용 가능한지 간단히 테스트
            // 실패해도 무시 - 실제 쿼리에서 PostGIS 함수 사용 시 오류 발생
            try {
                stmt.execute("SELECT 1 WHERE EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'st_makepoint')");
            } catch (Exception ignored) {
                // PostGIS 함수 확인 실패 - 무시
            }
            
        } catch (Exception e) {
            // 연결 실패 시에만 경고
            System.err.println("Warning: Failed to configure PostGIS settings: " + e.getMessage());
        }
    }
}