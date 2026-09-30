package com.consense.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ConsenseProperties props;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.getCors().getAllowedOrigins().split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        // 用 allowedOriginPatterns 而非 allowedOrigins：
        // allowCredentials(true) 时 Spring 禁止 allowedOrigins 含通配符，
        // 而 pattern（如 http://*:5173）只有在 allowedOriginPatterns 下才合法——
        // 这是为了让局域网 IP（http://10.x.x.x:5173）访问前端时不再被 CORS 403
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
