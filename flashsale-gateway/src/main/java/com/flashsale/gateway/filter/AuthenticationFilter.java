package com.flashsale.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT认证全局过滤器
 */
@Slf4j
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final String SECRET_KEY = "flashsale-secret-key-2024-graduation-project";
    private static final String TOKEN_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String PHONE_HEADER = "X-Phone";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 不需要认证的路径
     */
    private static final List<String> EXCLUDE_PATHS = List.of(
            "/api/user/login",
            "/api/user/register",
            "/api/activity/list",
            "/api/activity/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 跳过不需要认证的路径
        if (isExcludePath(path)) {
            return chain.filter(exchange);
        }

        // 获取Token
        String token = request.getHeaders().getFirst(TOKEN_HEADER);
        if (!StringUtils.hasText(token) || !token.startsWith(TOKEN_PREFIX)) {
            return unauthorized(exchange.getResponse(), "Token不存在或格式错误");
        }

        token = token.substring(TOKEN_PREFIX.length());

        try {
            // 验证Token
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 检查是否过期
            if (claims.getExpiration().before(new java.util.Date())) {
                return unauthorized(exchange.getResponse(), "Token已过期");
            }

            // 提取用户信息并添加到请求头
            Long userId = claims.get("userId", Long.class);
            String phone = claims.get("phone", String.class);

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(USER_ID_HEADER, String.valueOf(userId))
                    .header(PHONE_HEADER, phone)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            log.error("Token验证失败: {}", e.getMessage());
            return unauthorized(exchange.getResponse(), "Token验证失败");
        }
    }

    /**
     * 判断是否为排除路径
     */
    private boolean isExcludePath(String path) {
        return EXCLUDE_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 返回未授权响应
     */
    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 2006);
        result.put("message", message);
        result.put("data", null);

        try {
            DataBuffer buffer = response.bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(result));
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100; // 优先级最高
    }
}
