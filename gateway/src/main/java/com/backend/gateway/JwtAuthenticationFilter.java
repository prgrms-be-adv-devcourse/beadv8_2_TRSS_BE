package com.backend.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

/**
 * Access Token(Authorization: Bearer)을 열어 회원 정보를 서비스로 넘기고, 로그인·역할이 맞지 않는 요청은 서비스로 넘기지 않는다.
 * <ol>
 *   <li>밖에서 들어온 X-Member-Id·X-Member-Role 헤더는 항상 지운다. 서비스는 이 헤더를 그대로 믿으므로 지어낸 값이 가면 안 된다</li>
 *   <li>토큰이 정상이면 회원 id·역할을 두 헤더로 넣는다. 공개 경로도 마찬가지다(상품 목록·추천은 로그인했으면 그 회원 기준으로 답한다)</li>
 *   <li>공개 경로는 토큰이 없거나 잘못돼도 헤더 없이 넘긴다(서비스가 비회원으로 처리)</li>
 *   <li>로그인 필요 경로는 토큰이 없거나 잘못되면 401, 역할이 맞지 않으면 403</li>
 * </ol>
 * 서비스(common JwtTokenFilter)는 이 헤더로 회원 정보를 만든다. 승인된 판매자인지, 내 주문인지 같은 DB 확인은 각 서비스가 한다.
 * 경로 규칙에 맞는 요청에만 돈다. 규칙에 없는 경로(/internal/** 등)는 이 필터 전에 404다.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    // 서비스로 넘기는 회원 정보 헤더. common JwtTokenFilter가 같은 이름으로 읽는다. 바꾸면 같이 바꾼다
    public static final String MEMBER_ID_HEADER = "X-Member-Id";
    public static final String MEMBER_ROLE_HEADER = "X-Member-Role";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";
    // 토큰에 들어가는 역할(common MemberRole). 판매자·인플루언서는 역할이 아니라 USER이고 승인 상태는 DB에 있다
    private static final Set<String> MEMBER_ROLES = Set.of("USER", "ADMIN", "LOGISTICS");

    // 공개 경로. common SecurityConfig의 SWAGGER_URLS·PUBLIC_*_API_URLS와 같아야 한다. 한쪽을 고치면 다른 쪽도 같이 고친다
    private static final List<PathPattern> PUBLIC_PATHS = patterns(
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error"
    );
    private static final List<PathPattern> PUBLIC_GET_PATHS = patterns(
            "/api/v1/ai/recommendations",
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/influencers/*",
            "/images/**"
    );
    private static final List<PathPattern> PUBLIC_POST_PATHS = patterns(
            "/api/v1/auth/email/**",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/kakao",
            "/api/v1/auth/refresh",
            "/webhooks/delivery-tracker/**",
            "/api/v1/topups/hecto/**"
    );
    // 인플루언서 프로필(/api/v1/influencers/*)은 공개지만 내 신청 정보는 로그인 필요. 공개 규칙보다 먼저 본다
    private static final PathPattern INFLUENCER_ME_PATH = PathPatternParser.defaultInstance.parse("/api/v1/influencers/me");

    // 경로별 역할. common SecurityConfig의 ADMIN·LOGISTICS·SELLER·INFLUENCER·BUYER·WITHDRAWAL_API_URLS와 같아야 한다. 한쪽을 고치면 다른 쪽도 같이 고친다
    // 공개 경로가 아닐 때만 본다(공개 경로 규칙이 먼저인 것도 common과 같다). 여기 없는 경로는 로그인만 하면 된다
    private static final List<PathPattern> ADMIN_PATHS = patterns("/api/v1/admin/**");
    private static final List<PathPattern> LOGISTICS_PATHS = patterns("/api/v1/logistics/**");
    // 구매자·판매자·인플루언서 기능. 관리자·물류 담당자는 막는다(판매자·인플루언서 승인 여부는 각 서비스가 DB로 본다)
    private static final List<PathPattern> USER_PATHS = patterns(
            "/api/v1/seller/**",
            "/api/v1/influencer/**",
            "/api/v1/ai/survey/**",
            "/api/v1/ai/recommendations/creators",
            "/api/v1/cart/**",
            "/api/v1/orders/**",
            "/api/v1/wishlist/**",
            "/api/v1/reviews/**",
            "/api/v1/coupons/**",
            "/api/v1/users/me/coupon-usages",
            "/api/v1/topups/**",
            "/api/v1/wallet/**",
            "/api/v1/payments/**",
            "/api/v1/sellers/**",
            "/api/v1/influencers/**",
            "/api/v1/users/me/withdrawal"
    );
    // 회원 탈퇴(DELETE)만 구매자 전용. 같은 경로의 내 정보 조회(GET)는 역할을 가리지 않는다
    private static final PathPattern MY_ACCOUNT_PATH = PathPatternParser.defaultInstance.parse("/api/v1/users/me");

    // 응답 본문은 서비스(common GlobalErrorCode + RsData)와 같은 모양이다. 프론트가 resultCode로 토큰 재발급을 고른다
    private static final byte[] UNAUTHORIZED_BODY =
            "{\"resultCode\":\"401-UNAUTHORIZED\",\"msg\":\"로그인이 필요합니다.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOKEN_EXPIRED_BODY =
            "{\"resultCode\":\"401-AUTH_TOKEN_EXPIRED\",\"msg\":\"로그인이 만료되었습니다. 다시 로그인해 주세요.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] FORBIDDEN_BODY =
            "{\"resultCode\":\"403-FORBIDDEN\",\"msg\":\"접근 권한이 없습니다.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_REQUEST_BODY =
            "{\"resultCode\":\"400-INVALID_REQUEST\",\"msg\":\"요청 형식이 올바르지 않습니다.\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);

    // 정리(정규화)되지 않은 경로 조각: 서비스(Tomcat)가 풀어서 다른 경로로 바꾼다. 디코딩 전 원문에서 찾는다(소문자로 바꿔 %2E·%2F도 잡는다)
    // 예: /api/v1/products/../orders는 gateway에선 공개 경로(products/**)로 보이지만 서비스에선 /api/v1/orders가 된다
    private static final List<String> UNNORMALIZED_PATH_PARTS = List.of("/../", "/./", "//", "\\", "%2e", "%2f", "%5c");

    private final SecretKey key;

    public JwtAuthenticationFilter(@Value("${custom.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(decodeSecret(secret));
    }

    // common JwtTokenProvider와 같은 검사. 키가 잘못 들어오면 시작할 때 바로 실패시킨다
    private static byte[] decodeSecret(String secret) {
        byte[] secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("custom.jwt.secret(JWT_SECRET)은 Base64 형식이어야 합니다. openssl rand -base64 32로 만드세요.", e);
        }
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "custom.jwt.secret(JWT_SECRET)은 디코딩 후 32바이트 이상이어야 합니다. .env에 JWT_SECRET= 처럼 빈 값이 있는지 확인하세요.");
        }
        return secretBytes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 공개 경로 판단을 속이는 주소는 서비스로 넘기지 않는다. 정상 화면·API는 이런 주소를 쓰지 않는다
        if (hasUnnormalizedPathPart(request.getPath().value())) {
            return reject(exchange.getResponse(), HttpStatus.BAD_REQUEST, INVALID_REQUEST_BODY);
        }
        boolean publicPath = isPublic(request);
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        TokenMember member = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                member = parseMember(header.substring(BEARER_PREFIX.length()));
            } catch (ExpiredJwtException e) {
                if (!publicPath) {
                    return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, TOKEN_EXPIRED_BODY);
                }
            } catch (JwtException | IllegalArgumentException e) {
                if (!publicPath) {
                    return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
                }
            }
        }

        if (!publicPath) {
            if (member == null) {
                return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
            }
            String requiredRole = requiredRole(request);
            if (requiredRole != null && !requiredRole.equals(member.role())) {
                return reject(exchange.getResponse(), HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
            }
        }
        return chain.filter(exchange.mutate().request(withMemberHeaders(request, member)).build());
    }

    // 밖에서 온 회원 헤더는 지우고, 토큰이 정상이면 토큰의 회원으로 다시 넣는다(공개 경로의 비회원이면 헤더 없음)
    private static ServerHttpRequest withMemberHeaders(ServerHttpRequest request, TokenMember member) {
        return request.mutate().headers(headers -> {
            headers.remove(MEMBER_ID_HEADER);
            headers.remove(MEMBER_ROLE_HEADER);
            if (member != null) {
                headers.set(MEMBER_ID_HEADER, member.memberId());
                headers.set(MEMBER_ROLE_HEADER, member.role());
            }
        }).build();
    }

    // 토큰을 열어 회원 id(subject)·역할을 꺼낸다. 만료면 ExpiredJwtException, 위조·형식 오류면 JwtException·IllegalArgumentException.
    // id가 숫자가 아니거나 역할이 없는 값이면 위조로 본다(common JwtTokenProvider도 이때 401)
    private TokenMember parseMember(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String memberId = claims.getSubject();
        String role = claims.get(ROLE_CLAIM, String.class);
        if (memberId == null || memberId.isEmpty() || !memberId.chars().allMatch(Character::isDigit) || !MEMBER_ROLES.contains(role)) {
            throw new IllegalArgumentException("토큰의 회원 id 또는 역할이 올바르지 않습니다");
        }
        return new TokenMember(memberId, role);
    }

    // 이 경로에 필요한 역할. null이면 로그인만 하면 된다
    private static String requiredRole(ServerHttpRequest request) {
        PathContainer path = request.getPath().pathWithinApplication();
        if (matchesAny(ADMIN_PATHS, path)) {
            return "ADMIN";
        }
        if (matchesAny(LOGISTICS_PATHS, path)) {
            return "LOGISTICS";
        }
        if (matchesAny(USER_PATHS, path)
                || (HttpMethod.DELETE.equals(request.getMethod()) && MY_ACCOUNT_PATH.matches(path))) {
            return "USER";
        }
        return null;
    }

    // 토큰에 든 회원. 헤더로 넘기므로 문자열 그대로 둔다
    private record TokenMember(String memberId, String role) {
    }

    private static boolean isPublic(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        // 브라우저가 본 요청 전에 보내는 CORS 확인 요청은 토큰을 싣지 않는다. 서비스의 CORS 설정이 답한다
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        PathContainer path = request.getPath().pathWithinApplication();
        if (matchesAny(PUBLIC_PATHS, path)) {
            return true;
        }
        if (HttpMethod.GET.equals(method)) {
            return !INFLUENCER_ME_PATH.matches(path) && matchesAny(PUBLIC_GET_PATHS, path);
        }
        return HttpMethod.POST.equals(method) && matchesAny(PUBLIC_POST_PATHS, path);
    }

    private static boolean hasUnnormalizedPathPart(String rawPath) {
        String path = rawPath.toLowerCase(Locale.ROOT);
        return path.endsWith("/..") || path.endsWith("/.") || UNNORMALIZED_PATH_PARTS.stream().anyMatch(path::contains);
    }

    private static Mono<Void> reject(ServerHttpResponse response, HttpStatus status, byte[] body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static List<PathPattern> patterns(String... paths) {
        return Stream.of(paths).map(PathPatternParser.defaultInstance::parse).toList();
    }

    // 경로 규칙을 고른 뒤, 서비스로 넘기기(NettyRoutingFilter) 전에 돈다
    @Override
    public int getOrder() {
        return -1;
    }
}
