package com.flashsale.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 防刷过滤器 - 防止恶意刷接口
 */
@Slf4j
@Component
public class AntiBrushFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 防刷配置
     */
    private static final int MAX_REQUESTS_PER_MINUTE = 60;  // 每分钟最大请求数
    private static final int BLACKLIST_THRESHOLD = 200;     // 触发黑名单的阈值
    private static final Duration BLACKLIST_TTL = Duration.ofHours(1); // 黑名单过期时间

    /**
     * 不需要防刷检查的路径
     */
    private static final String[] EXCLUDE_PATHS = {
            "/api/user/login",
            "/api/user/register",
            "/api/activity/list",
            "/api/activity/"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 跳过不需要防刷检查的路径
        for (String excludePath : EXCLUDE_PATHS) {
            if (path.startsWith(excludePath)) {
                return chain.filter(exchange);
            }
        }

        // 获取客户端标识
        String clientId = getClientId(request);

        // 检查是否在黑名单中
        String blacklistKey = "ip:blacklist:" + clientId;
        return redisTemplate.hasKey(blacklistKey)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        return blockedResponse(exchange.getResponse(), "触发防刷限制，请稍后再试");
                    }

                    // 检查请求频率
                    return checkRequestFrequency(clientId, exchange, chain);
                })
                .onErrorResume(e -> {
                    log.error("防刷异常: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    /**
     * 检查请求频率
     */
    private Mono<Void> checkRequestFrequency(String clientId, ServerWebExchange exchange,
                                              GatewayFilterChain chain) {
        String key = "anti:brush:" + clientId;
        long currentMinute = System.currentTimeMillis() / 60000; // 当前分钟
        String field = String.valueOf(currentMinute);

        // 增加计数
        return redisTemplate.opsForHash()
                .increment(key, field, 1)
                .flatMap(count -> {
                    // 设置过期时间
                    redisTemplate.expire(key, Duration.ofMinutes(5)).subscribe();

                    if (count > BLACKLIST_THRESHOLD) {
                        // 加入黑名单
                        String blacklistKey = "ip:blacklist:" + clientId;
                        return redisTemplate.opsForValue()
                                .set(blacklistKey, "1", BLACKLIST_TTL)
                                .then(blockedResponse(exchange.getResponse(),
                                        "请求过于频繁，已被限制访问"));
                    }

                    if (count > MAX_REQUESTS_PER_MINUTE) {
                        return blockedResponse(exchange.getResponse(),
                                "请求过于频繁，请稍后再试");
                    }

                    return chain.filter(exchange);
                });
    }

    /**
     * 获取客户端标识
     */
    private String getClientId(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId != null) {
            return "user:" + userId;
        }

        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return "unknown";
    }

    /**
     * 返回被拦截响应
     */
    private Mono<Void> blockedResponse(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 6002);
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
        return -98; // 在限流过滤器之后
    }
}
