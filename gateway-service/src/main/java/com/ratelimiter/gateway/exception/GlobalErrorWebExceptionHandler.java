package com.ratelimiter.gateway.exception;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "Internal Server Error";
        long retryAfter = 0;

        if (ex instanceof RateLimitExceededException rle) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            message = "Rate limit exceeded";
            retryAfter = rle.getRetryAfter();
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        if (retryAfter > 0) {
            // set(), not add(): RateLimitingFilter has already written this header and
            // add() would send it twice.
            exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));
        }

        String json = String.format("{\"error\": \"%s\", \"retryAfter\": %d}", message, retryAfter);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
