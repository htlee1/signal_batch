package gc.mda.signal_batch.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 로컬 타일 리소스 매핑 (대안 방법)
        registry.addResourceHandler("/tiles/**")
                .addResourceLocations("file:///devdata/MAPS/")
                .setCachePeriod(86400); // 24시간 캐시
        
        // 기본 정적 리소스
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // CORS 설정 (필요시)
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .maxAge(3600);
    }
    
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 루트 경로를 관리자 페이지로 리다이렉트
        registry.addRedirectViewController("/", "/admin/batch-admin.html");
        registry.addRedirectViewController("/index.html", "/admin/batch-admin.html");
    }
}