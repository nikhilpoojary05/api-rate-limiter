package com.ratelimiter.admin.repository;

import com.ratelimiter.admin.entity.TrafficEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TrafficEventRepository extends JpaRepository<TrafficEvent, Long> {

    List<TrafficEvent> findByTenantIdAndTimestampBetween(String tenantId, LocalDateTime from, LocalDateTime to);

    long countByTenantIdAndStatus(String tenantId, String status);

    Page<TrafficEvent> findByTenantIdOrderByTimestampDesc(String tenantId, Pageable pageable);

    Page<TrafficEvent> findAllByOrderByTimestampDesc(Pageable pageable);

    @Query("SELECT COUNT(t) FROM TrafficEvent t WHERE t.timestamp >= :since")
    long countEventsSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(t) FROM TrafficEvent t WHERE t.status = :status AND t.timestamp >= :since")
    long countByStatusSince(@Param("status") String status, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(AVG(t.latencyMs), 0) FROM TrafficEvent t")
    double getAverageLatency();

    @Query("SELECT t.tenantId, COUNT(t) as c FROM TrafficEvent t WHERE t.status = 'BLOCKED' GROUP BY t.tenantId ORDER BY c DESC LIMIT 5")
    List<Object[]> findTopBlockedTenants();
}
