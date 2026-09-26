package com.ratelimiter.admin.controller;

import com.ratelimiter.admin.dto.AnalyticsSummaryDto;
import com.ratelimiter.admin.dto.TimeSeriesPointDto;
import com.ratelimiter.admin.dto.TrafficEventDto;
import com.ratelimiter.admin.security.TenantAccess;
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

/**
 * Every endpoint here resolves the tenant through {@link TenantAccess} rather than
 * trusting the tenantId in the request: a plain ROLE_ADMIN is pinned to their own
 * tenant, and only ROLE_SUPER_ADMIN may read across tenants.
 */
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final TrafficEventService trafficEventService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final TenantAccess tenantAccess;

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummaryDto> getSummary(
            @RequestParam(required = false) String tenantId) {
        return ResponseEntity.ok(trafficEventService.getSummary(tenantAccess.resolveScope(tenantId)));
    }

    @GetMapping("/traffic")
    public ResponseEntity<List<TrafficEventDto>> getTrafficByTenant(
            @RequestParam String tenantId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        tenantAccess.requireAccessTo(tenantId);
        return ResponseEntity.ok(trafficEventService.getTrafficByTenant(tenantId, from, to));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<List<TimeSeriesPointDto>> getTimeSeriesData(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1h") String range) {
        return ResponseEntity.ok(
                trafficEventService.getTimeSeriesData(tenantAccess.resolveScope(tenantId), range));
    }

    @GetMapping("/events")
    public ResponseEntity<Page<TrafficEventDto>> getRecentEvents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                trafficEventService.getRecentEvents(tenantAccess.resolveScope(tenantId), page, size));
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveUpdates() {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 minutes
        sseEmitterRegistry.addEmitter(emitter, tenantAccess.current());
        return emitter;
    }

    /**
     * Manual event ingestion. The gateway publishes through Redis, so this exists only
     * for operational backfill — restricted to cross-tenant administrators because the
     * body names its own tenant.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> recordEvent(@RequestBody TrafficEventDto dto) {
        tenantAccess.requireSuperAdmin();
        trafficEventService.recordEvent(dto);
        sseEmitterRegistry.broadcast(dto);
        return ResponseEntity.ok().build();
    }
}
