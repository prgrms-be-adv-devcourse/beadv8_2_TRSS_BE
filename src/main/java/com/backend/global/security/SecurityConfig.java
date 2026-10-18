package com.backend.global.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 BCrypt 해시, JWT 인증, URL별 권한, CORS
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] SWAGGER_URLS = {
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };
    private static final String[] PUBLIC_API_URLS = {
            "/error"
    };
    private static final String[] PUBLIC_GET_API_URLS = {
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/images/**"   // 로컬 저장소의 상품 이미지 파일
    };
    private static final String[] PUBLIC_POST_API_URLS = {
            "/api/v1/auth/email/**",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    };
    private static final String[] ADMIN_API_URLS = {
            "/api/v1/admin/**"
    };
    private static final String[] SELLER_API_URLS = {
            "/api/v1/seller/**"
    };
    // 구매자 기능. 관리자는 운영 전용이라 구매·판매자 신청을 막음 (/api/v1/sellers/** (복수, 판매자 신청)는 /api/v1/seller/** (단수, 판매자 전용)와 다른 경로)
    private static final String[] BUYER_API_URLS = {
            "/api/v1/cart/**",
            "/api/v1/orders/**",
            "/api/v1/topups/**",
            "/api/v1/wallet/**",
            "/api/v1/sellers/**"
    };

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    @Value("${custom.cors.allowedOrigins}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .cors(cors -> cors.configurationSource(configurationSource()))
                .csrf(AbstractHttpConfigurer::disable)       // csrf 비활성화
                .httpBasic(AbstractHttpConfigurer::disable)  // Basic 인증 비활성화
                .formLogin(AbstractHttpConfigurer::disable)  // 로그인 폼 비활성화
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // 세션 방식 비활성화
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)  // 401
                        .accessDeniedHandler(accessDeniedHandler))           // 403
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SWAGGER_URLS).permitAll()
                        .requestMatchers(PUBLIC_API_URLS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_API_URLS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_API_URLS).permitAll()
                        .requestMatchers(ADMIN_API_URLS).hasRole("ADMIN")
                        // 판매자 여부는 역할이 아니라 판매자 상태(APPROVED)로 판단하므로, URL에서는 관리자만 막고 판매 기능에서 상태를 확인한다
                        .requestMatchers(SELLER_API_URLS).hasRole("USER")
                        .requestMatchers(BUYER_API_URLS).hasRole("USER")
                        .anyRequest().authenticated()
                )
                // 필터는 빈으로 등록하지 않음 (빈이면 서블릿 필터로도 자동 등록됨)
                .addFilterBefore(new JwtTokenFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource configurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));  // 모든 HTTP 메서드 허용
        configuration.setAllowedHeaders(List.of("*"));  // 모든 헤더값 허용
        configuration.setAllowCredentials(true);   // 자격 증명 허용 (Refresh Token 쿠키 전송)

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);  // 모든 url 패턴에 대해서 cors 허용 설정
        return source;
    }
}
