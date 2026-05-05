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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 防刷过滤器 - 防止恶意刷接口
 * 使用 Lua 脚本将 blacklist 检查 + 计数 + 过期合并为 1 次 Redis 调用
 */
@Slf4j
@Component
public class AntiBrushFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final int BLACKLIST_THRESHOLD = 200;
    private static final Duration BLACKLIST_TTL = Duration.ofHours(1);

    /**
     * Lua 脚本：黑名单检查 + 计数递增 + 过期设置
     * 返回值：
     *   -1  → 已在黑名单中
     *   -2  → 超过阈值，已加入黑名单
     *   >0  → 当前分钟内的请求数
     */
    private static final String LUA_SCRIPT =
            "local blacklistKey = KEYS[1]\n" +
            "local antiBrushKey = KEYS[2]\n" +
            "local field = ARGV[1]\n" +
            "local maxRequests = tonumber(ARGV[2])\n" +
            "local blacklistThreshold = tonumber(ARGV[3])\n" +
            "local ttl = tonumber(ARGV[4])\n" +
            "\n" +
            "-- 检查黑名单\n" +
            "if redis.call('EXISTS', blacklistKey) == 1 then\n" +
            "    return -1\n" +
            "end\n" +
            "\n" +
            "-- 递增计数\n" +
            "local count = redis.call('HINCRBY', antiBrushKey, field, 1)\n" +
            "redis.call('EXPIRE', antiBrushKey, ttl)\n" +
            "\n" +
            "-- 超过黑名单阈值\n" +
            "if count > blacklistThreshold then\n" +
            "    redis.call('SET', blacklistKey, '1', 'EX', 3600)\n" +
            "    return -2\n" +
            "end\n" +
            "\n" +
            "return count";

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

        for (String excludePath : EXCLUDE_PATHS) {
            if (path.startsWith(excludePath)) {
                return chain.filter(exchange);
            }
        }

        String clientId = getClientId(request);
        String blacklistKey = "ip:blacklist:" + clientId;
        String antiBrushKey = "anti:brush:" + clientId;
        String field = String.valueOf(System.currentTimeMillis() / 60000);

        List<String> keys = List.of(blacklistKey, antiBrushKey);
        List<String> args = List.of(
                field,
                String.valueOf(MAX_REQUESTS_PER_MINUTE),
                String.valueOf(BLACKLIST_THRESHOLD),
                String.valueOf(300) // 5 分钟过期
        );

        RedisScript<Long> script = RedisScript.of(LUA_SCRIPT, Long.class);

        return redisTemplate.execute(script, keys, args)
                .next()
                .flatMap(result -> {
                    if (result == null || result == -1) {
                        return blockedResponse(exchange.getResponse(), "触发防刷限制，请稍后再试");
                    }
                    if (result == -2) {
                        return blockedResponse(exchange.getResponse(), "请求过于频繁，已被限制访问");
                    }
                    if (result > MAX_REQUESTS_PER_MINUTE) {
                        return blockedResponse(exchange.getResponse(), "请求过于频繁，请稍后再试");
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    log.error("防刷异常: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

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
        return -98;
    }
}
