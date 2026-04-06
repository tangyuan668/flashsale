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
 * 限流过滤器 - 基于Redis令牌桶算法
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 限流配置
     */
    private static final int CAPACITY = 100;      // 桶容量
    private static final int REFILL_RATE = 10;    // 每秒补充令牌数
    private static final Duration WINDOW = Duration.ofSeconds(1);

    /**
     * Lua脚本：令牌桶算法
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local capacity = tonumber(ARGV[1]) " +
            "local tokens = tonumber(ARGV[2]) " +
            "local interval = tonumber(ARGV[3]) " +
            "local requested = tonumber(ARGV[4]) " +
            "" +
            "local current = redis.call('HMGET', key, 'tokens')[1] " +
            "local last_refill = redis.call('HMGET', key, 'last_refill')[1] " +
            "" +
            "if current == false then " +
            "    current = capacity " +
            "    last_refill = 0 " +
            "else " +
            "    current = tonumber(current) " +
            "    last_refill = tonumber(last_refill) " +
            "end " +
            "" +
            "local now = tonumber(ARGV[5]) " +
            "local delta = math.floor((now - last_refill) / interval) " +
            "" +
            "if delta > 0 then " +
            "    current = math.min(capacity, current + delta * tokens) " +
            "    last_refill = now " +
            "end " +
            "" +
            "if current >= requested then " +
            "    current = current - requested " +
            "    redis.call('HMSET', key, 'tokens', current, 'last_refill', last_refill) " +
            "    redis.call('EXPIRE', key, 3600) " +
            "    return 1 " +
            "else " +
            "    redis.call('HMSET', key, 'tokens', current, 'last_refill', last_refill) " +
            "    redis.call('EXPIRE', key, 3600) " +
            "    return 0 " +
            "end";

    /**
     * 不需要限流的路径
     */
    private static final String[] EXCLUDE_PATHS = {
            "/api/user/login",
            "/api/user/register"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 跳过不需要限流的路径
        for (String excludePath : EXCLUDE_PATHS) {
            if (path.startsWith(excludePath)) {
                return chain.filter(exchange);
            }
        }

        // 获取客户端标识（IP或用户ID）
        String clientId = getClientId(request);
        String key = "rate:limit:" + clientId;

        // 执行限流检查
        return redisTemplate.execute(
                        redisScript(),
                        keys(key),
                        values(String.valueOf(CAPACITY), String.valueOf(REFILL_RATE),
                                String.valueOf(WINDOW.getSeconds()), "1",
                                String.valueOf(System.currentTimeMillis() / 1000))
                )
                .next()
                .flatMap(result -> {
                    if ("1".equals(result)) {
                        return chain.filter(exchange);
                    } else {
                        return rateLimitExceeded(exchange.getResponse());
                    }
                })
                .onErrorResume(e -> {
                    log.error("限流异常: {}", e.getMessage());
                    // 限流异常时放行
                    return chain.filter(exchange);
                });
    }

    /**
     * 获取客户端标识
     */
    private String getClientId(ServerHttpRequest request) {
        // 优先使用用户ID
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId != null) {
            return "user:" + userId;
        }

        // 使用IP地址
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return "ip:" + xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return "ip:" + xRealIp;
        }

        return "ip:unknown";
    }

    /**
     * 返回限流响应
     */
    private Mono<Void> rateLimitExceeded(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 6001);
        result.put("message", "请求过于频繁，请稍后再试");
        result.put("data", null);

        try {
            DataBuffer buffer = response.bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(result));
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.setComplete();
        }
    }

    private org.springframework.data.redis.core.script.RedisScript<String> redisScript() {
        return org.springframework.data.redis.core.script.RedisScript.of(LUA_SCRIPT, String.class);
    }

    private Iterable<String> keys(String key) {
        return java.util.Collections.singletonList(key);
    }

    private Iterable<String> values(String... values) {
        return java.util.List.of(values);
    }

    @Override
    public int getOrder() {
        return -99; // 在认证过滤器之后
    }
}
