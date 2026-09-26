package com.ratelimiter.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.admin.dto.TrafficEventDto;
import com.ratelimiter.admin.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisTrafficConsumer {

    private final RedisTemplate<String, String> redisTemplate;
    private final TrafficEventService trafficEventService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    public void consumeEvents() {
        List<TrafficEventDto> batch = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                String eventJson = redisTemplate.opsForList().leftPop("traffic:events");
                if (eventJson == null) {
                    break;
                }
                TrafficEventDto event = objectMapper.readValue(eventJson, TrafficEventDto.class);
                batch.add(event);
            }

            if (!batch.isEmpty()) {
                for (TrafficEventDto event : batch) {
                    trafficEventService.recordEvent(event);
                    sseEmitterRegistry.broadcast(event);
                }
                log.debug("Processed {} traffic events", batch.size());
            }
        } catch (Exception e) {
            log.error("Error consuming traffic events from Redis", e);
        }
    }
}
