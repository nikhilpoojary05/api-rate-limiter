package com.ratelimiter.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.admin.entity.Tenant;
import com.ratelimiter.admin.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes the active tenants' API key hashes to Redis, where the gateway looks up the
 * key a client presents. Redis hash {@value #KEY}: field = SHA-256 of the key, value =
 * the tenant and tier the key acts as. Like the rate limit rules, it is written on
 * startup and on every change, and restored if Redis loses it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyPublisher {

    static final String KEY = "apikeys";

    private final TenantRepository tenants;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** Rebuilds the whole set and swaps it in with one RENAME, so the gateway never sees half of it. */
    public void publish() {
        Map<String, String> entries = new HashMap<>();
        for (Tenant tenant : tenants.findAllByActive(true)) {
            entries.put(tenant.getApiKeyHash(), identity(tenant));
        }
        if (entries.isEmpty()) {
            redisTemplate.delete(KEY);
        } else {
            String staging = KEY + ":staging:" + UUID.randomUUID();
            redisTemplate.opsForHash().putAll(staging, entries);
            redisTemplate.rename(staging, KEY);
        }
        log.info("Published {} API keys to Redis", entries.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishOnStartup() {
        try {
            publish();
        } catch (RuntimeException e) {
            log.error("API key publish on startup failed; the periodic check will retry", e);
        }
    }

    @Scheduled(fixedDelayString = "${apikeys.republish-ms:60000}",
               initialDelayString = "${apikeys.republish-ms:60000}")
    public void restoreIfMissing() {
        try {
            if (Boolean.FALSE.equals(redisTemplate.hasKey(KEY))) {
                log.warn("API keys missing from Redis; restoring them from the database");
                publish();
            }
        } catch (RuntimeException e) {
            log.error("API key check failed; retrying on the next run", e);
        }
    }

    private String identity(Tenant tenant) {
        try {
            return objectMapper.writeValueAsString(Map.of("tenantId", tenant.getTenantId(), "tier", tenant.getTier()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise tenant " + tenant.getTenantId(), e);
        }
    }
}
