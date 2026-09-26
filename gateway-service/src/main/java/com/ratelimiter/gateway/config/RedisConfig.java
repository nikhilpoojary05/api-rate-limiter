package com.ratelimiter.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

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