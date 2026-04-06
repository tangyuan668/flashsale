package com.flashsale.gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway全局异常处理器
 */
@Slf4j
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        // 设置响应头
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        int code;
        String message;
        HttpStatus httpStatus;

        if (ex instanceof ResponseStatusException rse) {
            httpStatus = (HttpStatus) rse.getStatusCode();
            code = httpStatus.value();
            message = rse.getReason() != null ? rse.getReason() : "请求错误";
        } else {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            code = 1000;
            message = "系统内部错误";
        }

        response.setStatusCode(httpStatus);

        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        result.put("data", null);

        log.error("Gateway异常: code={}, message={}", code, message, ex);

        try {
            DataBuffer buffer = response.bufferFactory()
                    .wrap(objectMapper.writeValueAsBytes(result));
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
