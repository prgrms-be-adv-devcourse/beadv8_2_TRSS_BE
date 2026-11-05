package com.backend.global.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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

import com.backend.global.exception.GlobalErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 BCrypt 해시, JWT 인증, URL별 권한, CORS
 * 서비스 간 호출(/internal/**)은 JWT 대신 내부 전용 키로 인증하는 체인을 따로 둠
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    // 서비스 간 호출 전용. 외부에는 열지 않음 (nginx는 /api/ 등만 앱으로 넘김)
    private static final String INTERNAL_API_URLS = "/internal/**";

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
            "/api/v1/auth/kakao",
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
            "/api/v1/wishlist/**",
            "/api/v1/topups/**",
            "/api/v1/wallet/**",
            "/api/v1/sellers/**"
    };
    // 회원 탈퇴(조건 조회 포함)도 구매자만. 관리자 계정은 탈퇴할 수 없음 (1번 관리자는 수수료를 받는 계정이라 탈퇴하면 정산 지급이 실패)
    private static final String[] WITHDRAWAL_API_URLS = {
            "/api/v1/users/me/withdrawal"
    };

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final SecurityErrorResponseWriter securityErrorResponseWriter;

    @Value("${custom.cors.allowedOrigins}")
    private List<String> allowedOrigins;

    // 서비스 간 호출용 내부 키. 기본값 없이 환경변수(.env)로만 주입
    @Value("${custom.internal.apiKey}")
    private String internalApiKey;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // /internal/** 전용 체인. 사용자 JWT는 보지 않으므로 사용자 토큰으로는 내부 API를 부를 수 없음
    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .securityMatcher(INTERNAL_API_URLS)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, e) ->
                                securityErrorResponseWriter.write(response, GlobalErrorCode.INTERNAL_API_KEY_INVALID)))  // 401
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"))
                .addFilterBefore(new InternalApiKeyFilter(internalApiKey), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
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
                        .requestMatchers(WITHDRAWAL_API_URLS).hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me").hasRole("USER")
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
