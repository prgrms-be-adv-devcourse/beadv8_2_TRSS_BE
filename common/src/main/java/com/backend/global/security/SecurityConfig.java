package com.backend.global.security;

import java.util.List;

import jakarta.servlet.DispatcherType;

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

    // 공개 경로(SWAGGER_URLS·PUBLIC_*_API_URLS, influencers/me 예외)는 gateway JwtAuthenticationFilter에도 같은 목록이 있다. 고치면 같이 고친다
    private static final String[] SWAGGER_URLS = {
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };
    private static final String[] PUBLIC_API_URLS = {
            "/error"
    };
    // 메트릭 수집(Alloy)·상태 확인. 노출하는 엔드포인트는 health·prometheus뿐이다(common observability-defaults.properties)
    // 컨테이너에서는 관리 포트(9091, Dockerfile의 MANAGEMENT_SERVER_PORT)에만 뜬다. nginx는 이 포트를 넘기지 않고 /actuator는 404로 막으며,
    // 포트를 호스트에 열지 않아 Docker 내부망에서만 닿는다. 앱 포트(808x)에는 actuator가 없다(로컬 bootRun만 앱 포트에 뜬다)
    // 노출 설정이 바뀌어(exposure.include=* 등) 다른 엔드포인트가 열려도 인증 없이는 닿지 않게, 쓰는 두 개만 GET으로 허용한다
    private static final String[] ACTUATOR_URLS = {
            "/actuator/health", "/actuator/health/**",   // 상태 확인(readiness·liveness 하위 경로 포함)
            "/actuator/prometheus"                        // 메트릭 수집(Alloy)
    };
    private static final String[] PUBLIC_GET_API_URLS = {
            "/api/v1/ai/recommendations",   // 맞춤 상품 추천(비회원은 최신순). 정확히 이 경로만, 크리에이터 추천은 로그인 필요
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/influencers/*",   // 인플루언서 프로필
            "/images/**"   // 로컬 저장소의 상품 이미지 파일
    };
    private static final String[] PUBLIC_POST_API_URLS = {
            "/api/v1/auth/email/**",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/kakao",
            "/api/v1/auth/refresh",
            "/webhooks/delivery-tracker/**",  // Delivery Tracker 콜백. URL 토큰으로 delivery가 직접 확인
            "/api/v1/topups/hecto/**"         // 헥토 결제창 결과(브라우저 POST)·결과 알림(헥토 서버). 프론트로 넘기기만 하고 승인은 토큰이 있는 승인 API에서 함
    };
    // 역할별 경로(ADMIN·LOGISTICS·SELLER·INFLUENCER·BUYER·WITHDRAWAL_API_URLS, DELETE /users/me)도 gateway JwtAuthenticationFilter에 같은 목록이 있다. 고치면 같이 고친다
    private static final String[] ADMIN_API_URLS = {
            "/api/v1/admin/**"
    };
    // 물류 담당자 기능(출고·송장 등록). 관리자·구매자도 막음
    private static final String[] LOGISTICS_API_URLS = {
            "/api/v1/logistics/**"
    };
    private static final String[] SELLER_API_URLS = {
            "/api/v1/seller/**"
    };
    // 인플루언서 전용 (/api/v1/influencers/** (복수, 인플루언서 신청, 프로필)와 다른 경로)
    private static final String[] INFLUENCER_API_URLS = {
            "/api/v1/influencer/**"
    };
    // 구매자 기능. 관리자는 운영 전용이라 구매·판매자 신청을 막음 (/api/v1/sellers/** (복수, 판매자 신청)는 /api/v1/seller/** (단수, 판매자 전용)와 다른 경로)
    private static final String[] BUYER_API_URLS = {
            "/api/v1/ai/survey/**",
            "/api/v1/ai/recommendations/creators",   // 피부가 비슷한 크리에이터 추천
            "/api/v1/cart/**",
            "/api/v1/orders/**",
            "/api/v1/wishlist/**",
            "/api/v1/reviews/**",
            "/api/v1/coupons/**",
            "/api/v1/users/me/coupon-usages",
            "/api/v1/topups/**",
            "/api/v1/wallet/**",
            "/api/v1/payments/**",   // 반품 환불 조회·회수 택배비 결제 (관리자 결제 조회는 /api/v1/admin/payments)
            "/api/v1/sellers/**",
            "/api/v1/influencers/**"   // 인플루언서 신청 (프로필 GET은 위에서 공개)
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

    // 헤더가 없을 때 Authorization 토큰을 직접 열지. 운영은 gateway가 넘긴 헤더만 받는다(compose.prod.yaml에서 false).
    // 기본 true: gateway 없이 서비스를 부르는 로컬 개발·서비스 테스트가 지금처럼 토큰으로 돈다(JwtTokenFilter)
    @Value("${custom.auth.acceptBearerToken:true}")
    private boolean acceptBearerToken;

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
                // HSTS는 HTTPS를 처리하는 nginx 한 곳에서만 붙인다. 앱도 붙이면 헤더가 두 개가 되고 브라우저는 앞의 것(앱 기본 1년)만 쓴다
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // 세션 방식 비활성화
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)  // 401
                        .accessDeniedHandler(accessDeniedHandler))           // 403
                .authorizeHttpRequests(auth -> auth
                        // 원래 요청에서 이미 인증·인가를 통과한 비동기 응답 마무리(SSE 종료)와 오류 처리 디스패치는 다시 검사하지 않음
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(SWAGGER_URLS).permitAll()
                        .requestMatchers(PUBLIC_API_URLS).permitAll()
                        .requestMatchers(HttpMethod.GET, ACTUATOR_URLS).permitAll()
                        // 인플루언서 프로필(/api/v1/influencers/*)은 공개지만 내 신청 정보는 로그인 필요. 공개 규칙보다 먼저 둔다
                        .requestMatchers(HttpMethod.GET, "/api/v1/influencers/me").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_API_URLS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_API_URLS).permitAll()
                        .requestMatchers(ADMIN_API_URLS).hasRole("ADMIN")
                        .requestMatchers(LOGISTICS_API_URLS).hasRole("LOGISTICS")
                        // 판매자 여부는 역할이 아니라 판매자 상태(APPROVED)로 판단하므로, URL에서는 관리자만 막고 판매 기능에서 상태를 확인한다
                        .requestMatchers(SELLER_API_URLS).hasRole("USER")
                        // 인플루언서도 역할이 아니라 인플루언서 상태(APPROVED)로 판단하므로, URL에서는 관리자만 막고 기능에서 상태를 확인한다
                        .requestMatchers(INFLUENCER_API_URLS).hasRole("USER")
                        .requestMatchers(BUYER_API_URLS).hasRole("USER")
                        .requestMatchers(WITHDRAWAL_API_URLS).hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me").hasRole("USER")
                        .anyRequest().authenticated()
                )
                // 필터는 빈으로 등록하지 않음 (빈이면 서블릿 필터로도 자동 등록됨)
                .addFilterBefore(new JwtTokenFilter(jwtTokenProvider, acceptBearerToken), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource configurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));  // 모든 HTTP 메서드 허용
        configuration.setAllowedHeaders(List.of("*"));  // 모든 헤더값 허용
        configuration.setAllowCredentials(true);   // 자격 증명 허용 (Refresh Token 쿠키 전송)

        // 헥토 결제창 결과(브라우저 폼 POST)는 결제창 출처(헥토 도메인)에서 오므로 출처를 가리지 않음. 쿠키 없이 받고 프론트로 넘기기만 함
        CorsConfiguration hecto = new CorsConfiguration();
        hecto.setAllowedOriginPatterns(List.of("*"));
        hecto.setAllowedMethods(List.of("POST"));
        hecto.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/topups/hecto/**", hecto);  // "/**"보다 먼저 등록해야 먼저 매칭됨
        source.registerCorsConfiguration("/**", configuration);  // 모든 url 패턴에 대해서 cors 허용 설정
        return source;
    }
}
