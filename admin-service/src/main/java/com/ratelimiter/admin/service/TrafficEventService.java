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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

    public AnalyticsSummaryDto getSummary() {
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        long total = repository.countEventsSince(last24h);
        long blocked = repository.countByStatusSince("BLOCKED", last24h);
        double blockRate = total > 0 ? (double) blocked / total * 100 : 0;
        double avgLatency = repository.getAverageLatency();
        long activeTenants = tenantRepository.countByActive(true);

        List<Object[]> topBlocked = repository.findTopBlockedTenants();
        List<TenantTrafficStat> topBlockedList = topBlocked.stream()
                .map(obj -> new TenantTrafficStat((String) obj[0], ((Number) obj[1]).longValue()))
                .collect(Collectors.toList());

        return new AnalyticsSummaryDto(total, blocked, blockRate, avgLatency, activeTenants, topBlockedList);
    }

    public List<TimeSeriesPointDto> getTimeSeriesData(String tenantId, String range) {
        return new ArrayList<>();
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
