package com.ratelimiter.admin.service;

import com.ratelimiter.admin.dto.AnalyticsSummaryDto;
import com.ratelimiter.admin.dto.TenantTrafficStat;
import com.ratelimiter.admin.dto.TimeSeriesPointDto;
import com.ratelimiter.admin.dto.TrafficEventDto;
import com.ratelimiter.admin.entity.TrafficEvent;
import com.ratelimiter.admin.repository.TenantRepository;
import com.ratelimiter.admin.repository.TrafficEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrafficEventService {

    private final TrafficEventRepository repository;
    private final TenantRepository tenantRepository;

    public TrafficEvent recordEvent(TrafficEventDto dto) {
        TrafficEvent event = TrafficEvent.builder()
                .tenantId(dto.getTenantId())
                .userId(dto.getUserId())
                .ipAddress(dto.getIpAddress())
                .path(dto.getPath())
                .method(dto.getMethod())
                .status(dto.getStatus())
                .latencyMs(dto.getLatencyMs())
                .httpStatus(dto.getHttpStatus())
                .timestamp(dto.getTimestamp() != null ? LocalDateTime.ofInstant(dto.getTimestamp(), ZoneId.systemDefault()) : LocalDateTime.now())
                .build();
        return repository.save(event);
    }

    public List<TrafficEventDto> getTrafficByTenant(String tenantId, Instant from, Instant to) {
        LocalDateTime fromDt = LocalDateTime.ofInstant(from, ZoneId.systemDefault());
        LocalDateTime toDt = LocalDateTime.ofInstant(to, ZoneId.systemDefault());
        return repository.findByTenantIdAndTimestampBetween(tenantId, fromDt, toDt)
                .stream().map(this::mapToDto).collect(Collectors.toList());
    }

    /**
     * @param tenantId restrict every figure to this tenant, or null for a cross-tenant
     *                 summary. Only callers with cross-tenant rights may pass null —
     *                 see TenantAccess.resolveScope.
     */
    public AnalyticsSummaryDto getSummary(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return getSummaryForTenant(tenantId);
        }
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        long total = repository.countEventsSince(last24h);
        long blocked = repository.countByStatusSince("BLOCKED", last24h);
        double blockRate = total > 0 ? (double) blocked / total * 100 : 0;
        double avgLatency = repository.getAverageLatencySince(last24h);
        long activeTenants = tenantRepository.countByActive(true);

        List<Object[]> topBlocked = repository.findTopBlockedTenantsSince(last24h);
        List<TenantTrafficStat> topBlockedList = topBlocked.stream()
                .map(obj -> new TenantTrafficStat((String) obj[0], ((Number) obj[1]).longValue()))
                .collect(Collectors.toList());

        return new AnalyticsSummaryDto(total, blocked, blockRate, avgLatency, activeTenants, topBlockedList);
    }

    private AnalyticsSummaryDto getSummaryForTenant(String tenantId) {
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        long total = repository.countEventsSinceForTenant(tenantId, last24h);
        long blocked = repository.countByStatusSinceForTenant(tenantId, "BLOCKED", last24h);
        double blockRate = total > 0 ? (double) blocked / total * 100 : 0;
        double avgLatency = repository.getAverageLatencySinceForTenant(tenantId, last24h);

        // The caller sees only their own tenant, so the "top blocked" list is just them.
        List<TenantTrafficStat> topBlocked = blocked > 0
                ? List.of(new TenantTrafficStat(tenantId, blocked))
                : List.of();

        return new AnalyticsSummaryDto(total, blocked, blockRate, avgLatency, 1, topBlocked);
    }

    /** The dashboard's ranges, each with a bucket width that keeps the chart readable. */
    enum Range {
        LAST_HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1)),
        LAST_6_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(5)),
        LAST_DAY("24h", Duration.ofHours(24), Duration.ofMinutes(30)),
        LAST_WEEK("7d", Duration.ofDays(7), Duration.ofHours(6));

        final String label;
        final Duration window;
        final Duration bucket;

        Range(String label, Duration window, Duration bucket) {
            this.label = label;
            this.window = window;
            this.bucket = bucket;
        }

        static Range parse(String label) {
            for (Range range : values()) {
                if (range.label.equals(label)) {
                    return range;
                }
            }
            throw new IllegalArgumentException("range must be one of 1h, 6h, 24h, 7d");
        }
    }

    /**
     * Allowed, blocked and average latency per bucket across the range, oldest first.
     * This used to return an empty list, which is why the dashboard drew random numbers.
     *
     * @param tenantId restrict to this tenant, or null for every tenant (cross-tenant
     *                 callers only — see TenantAccess.resolveScope)
     */
    public List<TimeSeriesPointDto> getTimeSeriesData(String tenantId, String range) {
        Range r = Range.parse(range);
        long bucketSeconds = r.bucket.getSeconds();
        long lastBucket = Math.floorDiv(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC), bucketSeconds);
        long firstBucket = lastBucket - r.window.getSeconds() / bucketSeconds + 1;
        LocalDateTime since = LocalDateTime.ofEpochSecond(firstBucket * bucketSeconds, 0, ZoneOffset.UTC);

        String scope = tenantId == null || tenantId.isBlank() ? null : tenantId;
        List<Object[]> rows = repository.aggregateByBucket(scope, since, bucketSeconds);
        return fillBuckets(rows, firstBucket, lastBucket, bucketSeconds);
    }

    /**
     * Turns the aggregated rows into one point per bucket, zeros where nothing happened,
     * so the chart's time axis is continuous. Timestamps are stored as wall-clock time in
     * the system zone (see recordEvent), so buckets convert back through the same zone.
     */
    static List<TimeSeriesPointDto> fillBuckets(List<Object[]> rows, long firstBucket,
                                                long lastBucket, long bucketSeconds) {
        Map<Long, Object[]> byBucket = new HashMap<>();
        for (Object[] row : rows) {
            byBucket.put(((Number) row[0]).longValue(), row);
        }
        List<TimeSeriesPointDto> points = new ArrayList<>();
        for (long bucket = firstBucket; bucket <= lastBucket; bucket++) {
            Instant start = LocalDateTime.ofEpochSecond(bucket * bucketSeconds, 0, ZoneOffset.UTC)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Object[] row = byBucket.get(bucket);
            points.add(row == null
                    ? new TimeSeriesPointDto(start, 0, 0, 0)
                    : new TimeSeriesPointDto(start,
                            ((Number) row[1]).longValue(),
                            ((Number) row[2]).longValue(),
                            ((Number) row[3]).doubleValue()));
        }
        return points;
    }

    public Page<TrafficEventDto> getRecentEvents(String tenantId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<TrafficEvent> events;
        if (tenantId != null && !tenantId.isEmpty()) {
            events = repository.findByTenantIdOrderByTimestampDesc(tenantId, pageable);
        } else {
            events = repository.findAllByOrderByTimestampDesc(pageable);
        }
        return events.map(this::mapToDto);
    }

    private TrafficEventDto mapToDto(TrafficEvent entity) {
        TrafficEventDto dto = new TrafficEventDto();
        dto.setTenantId(entity.getTenantId());
        dto.setUserId(entity.getUserId());
        dto.setIpAddress(entity.getIpAddress());
        dto.setPath(entity.getPath());
        dto.setMethod(entity.getMethod());
        dto.setStatus(entity.getStatus());
        dto.setLatencyMs(entity.getLatencyMs());
        dto.setHttpStatus(entity.getHttpStatus());
        if (entity.getTimestamp() != null) {
            dto.setTimestamp(entity.getTimestamp().atZone(ZoneId.systemDefault()).toInstant());
        }
        return dto;
    }
}
