package com.ratelimiter.gateway.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.Delay;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class RedisConfig {

    /**
     * Fail fast while Redis is down. By default Lettuce queues commands while it is
     * disconnected and waits up to 60 s for each, so a Redis outage left every request
     * hanging: the chaos test saw requests take over 10 s and the gateway answer about
     * one request in ten seconds. Rejecting at once lets the limiter fail closed
     * immediately. Timeouts come from spring.data.redis.timeout and connect-timeout.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer failFastWhileRedisIsDown(RedisProperties redis) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(redis.getConnectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled(redis.getTimeout()))
                .build());
    }

    /**
     * With commands rejected rather than queued, recovery waits for the next reconnect
     * attempt. Lettuce backs off up to 30 s by default; capped at 2 s so service resumes
     * within seconds of Redis coming back.
     */
    @Bean
    public ClientResourcesBuilderCustomizer quickReconnect() {
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(100), Duration.ofSeconds(2), 2, TimeUnit.MILLISECONDS));
    }

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        StringRedisSerializer serializer = new StringRedisSerializer();

        RedisSerializationContext<String, String> context =
                RedisSerializationContext
                        .<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public RedisScript<List> slidingWindowScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/sliding_window.lua"),
                List.class
        );
    }

    @Bean
    public RedisScript<List> tokenBucketScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/token_bucket.lua"),
                List.class
        );
    }
}