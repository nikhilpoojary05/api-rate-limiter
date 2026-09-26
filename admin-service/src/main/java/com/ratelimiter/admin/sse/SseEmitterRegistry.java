package com.ratelimiter.admin.sse;

import com.ratelimiter.admin.dto.AnalyticsSummaryDto;
import com.ratelimiter.admin.dto.TrafficEventDto;
import com.ratelimiter.admin.security.AdminPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Holds the open SSE connections for the live traffic view.
 *
 * <p>Two things this guards against. First, subscribers only receive events for their
 * own tenant — the stream carries client IPs, user ids and request paths, so a plain
 * tenant admin must not see another tenant's traffic. Second, the number of open
 * emitters is capped: an unbounded list let anyone hold the service's memory open by
 * opening connections.
 */
@Component
@Slf4j
public class SseEmitterRegistry {

    /** One open stream per subscriber, tagged with who is allowed to see what. */
    private record Subscriber(SseEmitter emitter, String tenantId, boolean superAdmin) {
        boolean canSee(String eventTenantId) {
            return superAdmin || (tenantId != null && tenantId.equals(eventTenantId));
        }
    }

    @Value("${analytics.sse.max-connections:200}")
    private int maxConnections;

    @Value("${analytics.sse.max-connections-per-tenant:20}")
    private int maxConnectionsPerTenant;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public void addEmitter(SseEmitter emitter, AdminPrincipal principal) {
        if (subscribers.size() >= maxConnections) {
            log.warn("Rejecting SSE subscription: {} connections already open", subscribers.size());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many live connections open, try again shortly");
        }
        long forTenant = subscribers.stream()
                .filter(s -> s.tenantId() != null && s.tenantId().equals(principal.tenantId()))
                .count();
        if (forTenant >= maxConnectionsPerTenant) {
            log.warn("Rejecting SSE subscription for tenant {}: {} already open",
                    principal.tenantId(), forTenant);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many live connections open for this tenant");
        }

        Subscriber subscriber =
                new Subscriber(emitter, principal.tenantId(), principal.isSuperAdmin());
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));

        log.debug("SSE subscriber added for tenant={} (total {})",
                principal.tenantId(), subscribers.size());
    }

    public void broadcast(TrafficEventDto event) {
        send("trafficEvent", event, s -> s.canSee(event.getTenantId()));
    }

    /** Summary figures are aggregated across tenants, so only super-admins receive them. */
    public void broadcastSummary(AnalyticsSummaryDto summary) {
        send("summary", summary, Subscriber::superAdmin);
    }

    @Scheduled(fixedRate = 15000)
    public void sendPing() {
        send("ping", "keep-alive", s -> true);
    }

    private void send(String eventName, Object payload, Predicate<Subscriber> audience) {
        List<Subscriber> dead = new java.util.ArrayList<>();
        for (Subscriber subscriber : subscribers) {
            if (!audience.test(subscriber)) {
                continue;
            }
            try {
                subscriber.emitter().send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                dead.add(subscriber);
            }
        }
        subscribers.removeAll(dead);
    }
}
