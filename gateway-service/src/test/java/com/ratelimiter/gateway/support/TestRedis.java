package com.ratelimiter.gateway.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * A real Redis for the rate limiter tests. The limiters are Lua scripts, so mocking Redis
 * would test nothing that matters — atomicity and the script arithmetic are the point.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code REDIS_TEST_HOST} / {@code REDIS_TEST_PORT} / {@code REDIS_TEST_PASSWORD}, if set</li>
 *   <li>a throwaway {@code redis:7-alpine} container, when a Docker daemon is available (CI)</li>
 *   <li>a Redis already listening on localhost:6379 (local development)</li>
 * </ol>
 * If none is reachable the tests are skipped, not failed.
 *
 * <p>Against a shared Redis the tests only touch keys under a random per-test prefix and
 * delete them afterwards; nothing is flushed.
 */
public final class TestRedis {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;
    private static ReactiveRedisTemplate<String, String> template;
    private static String description;

    private TestRedis() {
    }

    public static synchronized ReactiveRedisTemplate<String, String> template() {
        if (template == null) {
            RedisStandaloneConfiguration config = resolve();
            Assumptions.assumeTrue(config != null,
                    "No Redis available: set REDIS_TEST_HOST, start Docker, or run Redis on localhost:6379");
            factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            factory.start();
            template = new ReactiveRedisTemplate<>(factory, RedisSerializationContext.string());
        }
        return template;
    }

    public static String description() {
        return description;
    }

    @SuppressWarnings("rawtypes")
    public static RedisScript<List> slidingWindowScript() {
        return RedisScript.of(new ClassPathResource("scripts/sliding_window.lua"), List.class);
    }

    @SuppressWarnings("rawtypes")
    public static RedisScript<List> tokenBucketScript() {
        return RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);
    }

    private static RedisStandaloneConfiguration resolve() {
        String envHost = System.getenv("REDIS_TEST_HOST");
        if (envHost != null && !envHost.isBlank()) {
            int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(envHost, port);
            String password = System.getenv("REDIS_TEST_PASSWORD");
            if (password != null && !password.isBlank()) {
                config.setPassword(password);
            }
            description = "REDIS_TEST_HOST " + envHost + ":" + port;
            return config;
        }

        if (dockerAvailable()) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            description = "Testcontainers redis:7-alpine";
            return new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379));
        }

        if (reachable("localhost", 6379)) {
            description = "local Redis on localhost:6379 (isolated keys)";
            return new RedisStandaloneConfiguration("localhost", 6379);
        }
        return null;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
