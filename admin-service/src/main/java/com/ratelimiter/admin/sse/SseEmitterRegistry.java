package com.ratelimiter.admin.sse;

import com.ratelimiter.admin.dto.AnalyticsSummaryDto;
import com.ratelimiter.admin.dto.TrafficEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class SseEmitterRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void addEmitter(SseEmitter emitter) {
        this.emitters.add(emitter);
        emitter.onCompletion(() -> this.emitters.remove(emitter));
        emitter.onTimeout(() -> this.emitters.remove(emitter));
        emitter.onError((e) -> this.emitters.remove(emitter));
    }

    public void broadcast(TrafficEventDto event) {
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("trafficEvent").data(event));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    public void broadcastSummary(AnalyticsSummaryDto summary) {
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("summary").data(summary));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    @Scheduled(fixedRate = 15000)
    public void sendPing() {
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }
}
