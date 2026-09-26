package com.ratelimiter.admin.controller;

import com.ratelimiter.admin.dto.AnalyticsSummaryDto;
import com.ratelimiter.admin.dto.TimeSeriesPointDto;
import com.ratelimiter.admin.dto.TrafficEventDto;
import com.ratelimiter.admin.service.TrafficEventService;
import com.ratelimiter.admin.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final TrafficEventService trafficEventService;
    private final SseEmitterRegistry sseEmitterRegistry;

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummaryDto> getSummary() {
        return ResponseEntity.ok(trafficEventService.getSummary());
    }

    @GetMapping("/traffic")
    public ResponseEntity<List<TrafficEventDto>> getTrafficByTenant(
            @RequestParam String tenantId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return ResponseEntity.ok(trafficEventService.getTrafficByTenant(tenantId, from, to));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<List<TimeSeriesPointDto>> getTimeSeriesData(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1h") String range) {
        return ResponseEntity.ok(trafficEventService.getTimeSeriesData(tenantId, range));
    }

    @GetMapping("/events")
    public ResponseEntity<Page<TrafficEventDto>> getRecentEvents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(trafficEventService.getRecentEvents(tenantId, page, size));
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveUpdates() {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 minutes
        sseEmitterRegistry.addEmitter(emitter);
        return emitter;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> recordEvent(@RequestBody TrafficEventDto dto) {
        trafficEventService.recordEvent(dto);
        sseEmitterRegistry.broadcast(dto);
        return ResponseEntity.ok().build();
    }
}
