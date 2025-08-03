package gc.mda.signal_batch.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "선박 항적 집계 및 조회 시스템 API",
        version = "2.0.0",
        description = """
            실시간 선박 위치 데이터를 계층적으로 집계하여 빠른 항적 조회 성능을 제공하는 시스템
            
            ## 핵심 기능
            ### 1. 계층적 항적 데이터 집계
            - **5분 단위 집계**: 실시간 선박 위치를 5분마다 LineStringM 형식의 항적으로 변환
            - **1시간 단위 집계**: 5분 데이터를 시간별로 병합 (매시 10분 실행)
            - **1일 단위 집계**: 시간별 데이터를 일별로 병합 (매일 01:00 실행)
            
            ### 2. 공간 기반 항적 관리
            - **해구별 집계**: 대해구(0.5°×0.5°) 및 소해구(대해구 3×3 분할) 단위 항적 저장
            - **사용자 정의 영역**: 임의 폴리곤 영역별 항적 집계
            - **타일 기반 조회**: 줌 레벨에 따른 동적 데이터 간소화
            
            ### 3. 비정상 항적 검출 및 필터링
            - **실시간 검출**: 물리적 불가능 항적 자동 필터링 (속도 100knots, 거리 10nm/5분 초과)
            - **항공기 예외처리**: sig_src_cd='000019' 항공기는 300knots/30nm 기준 적용
            - **분리 저장**: 비정상 항적은 별도 테이블(t_abnormal_tracks)에 보관
            
            ### 4. WebSocket 기반 대용량 항적 스트리밍
            - **STOMP over WebSocket**: 실시간 양방향 통신
            - **청크 스트리밍**: 대용량 데이터를 작은 단위로 분할 전송
            - **적응형 간소화**: 조회 범위와 기간에 따른 자동 데이터 최적화
            - **병렬 처리**: Daily → Hourly → 5min 순서로 병렬 조회
            
            ### 5. 실시간 모니터링 대시보드
            - **GIS 시각화**: deck.gl 기반 해구/영역별 선박 분포 표시
            - **항적 애니메이션**: 시간 흐름에 따른 선박 이동 재생
            - **성능 모니터링**: 배치 Job 실행 상태, 처리 통계, 시스템 리소스 실시간 확인
            
            ## 데이터 구조
            - **항적 형식**: PostGIS LineStringM (X: 경도, Y: 위도, M: 시간)
            - **위치 정보**: JSONB {lat, lon, time, sog}
            - **집계 통계**: vessel_count, total_distance, avg_speed
            
            ## 시스템 아키텍처
            ```
            CollectDB (실시간) → Spring Batch (집계) → QueryDB (조회)
                                        ↓
                                   BatchDB (메타)
            ```
            """,
        contact = @Contact(
            name = "Signal Batch Team",
            email = "signal-batch@mda.gc"
        ),
        license = @License(
            name = "Internal Use Only"
        )
    ),
    servers = {
        @Server(url = "http://localhost:8090", description = "Local Development Server"),
        @Server(url = "http://10.26.252.48:8090", description = "Development Server (QueryDB)"),
        @Server(url = "http://10.26.252.39:8090", description = "Production Server")
    }
)
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi trackApi() {
        return GroupedOpenApi.builder()
                .group("1-track-api")
                .displayName("항적 조회 API")
                .pathsToMatch("/api/v1/tracks/**", "/api/v1/haegu/**", "/api/v1/areas/**")
                .build();
    }

    @Bean
    public GroupedOpenApi abnormalTrackApi() {
        return GroupedOpenApi.builder()
                .group("2-abnormal-track-api")
                .displayName("비정상 항적 검출 API")
                .pathsToMatch("/api/v1/abnormal-tracks/**")
                .build();
    }

    @Bean
    public GroupedOpenApi tileApi() {
        return GroupedOpenApi.builder()
                .group("3-tile-api")
                .displayName("타일 집계 API")
                .pathsToMatch("/api/v1/tiles/**", "/api/tiles/**")
                .build();
    }

    @Bean
    public GroupedOpenApi performanceApi() {
        return GroupedOpenApi.builder()
                .group("4-performance-api")
                .displayName("성능 최적화 API")
                .pathsToMatch("/api/v1/performance/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("5-admin-api")
                .displayName("관리자 API")
                .pathsToMatch("/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi monitoringApi() {
        return GroupedOpenApi.builder()
                .group("6-monitoring-api")
                .displayName("모니터링 API")
                .pathsToMatch("/monitor/**", "/actuator/**", "/api/websocket/**")
                .build();
    }
}
