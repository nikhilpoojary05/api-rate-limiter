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

    // Latency and top-blocked used to cover all time while the counts beside them on the
    // dashboard covered 24 h; every summary figure now takes the same window.

    @Query("SELECT COALESCE(AVG(t.latencyMs), 0) FROM TrafficEvent t WHERE t.timestamp >= :since")
    double getAverageLatencySince(@Param("since") LocalDateTime since);

    @Query("SELECT t.tenantId, COUNT(t) as c FROM TrafficEvent t WHERE t.status = 'BLOCKED' AND t.timestamp >= :since GROUP BY t.tenantId ORDER BY c DESC LIMIT 5")
    List<Object[]> findTopBlockedTenantsSince(@Param("since") LocalDateTime since);

    /**
     * Allowed count, blocked count and average latency per time bucket, aggregated in the
     * database so a 7-day range does not load every event. A bucket is
     * floor(epoch seconds / bucketSeconds) of the stored wall-clock timestamp; the caller
     * converts it back the same way and fills empty buckets with zeros.
     *
     * @param tenantId restrict to one tenant, or null for every tenant
     * @return rows of [bucket, allowed, blocked, avgLatency]
     */
    @Query(value = """
            SELECT CAST(FLOOR(EXTRACT(EPOCH FROM t.timestamp) / :bucketSeconds) AS BIGINT) AS bucket,
                   COUNT(*) FILTER (WHERE t.status = 'ALLOWED') AS allowed,
                   COUNT(*) FILTER (WHERE t.status = 'BLOCKED') AS blocked,
                   COALESCE(AVG(t.latency_ms), 0) AS avg_latency
            FROM traffic_events t
            WHERE t.timestamp >= :since
              AND (CAST(:tenantId AS TEXT) IS NULL OR t.tenant_id = CAST(:tenantId AS TEXT))
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> aggregateByBucket(@Param("tenantId") String tenantId,
                                     @Param("since") LocalDateTime since,
                                     @Param("bucketSeconds") long bucketSeconds);

    // Tenant-scoped variants of the above. The unscoped queries aggregate across every
    // tenant, which a single tenant's administrator must not see.

    @Query("SELECT COUNT(t) FROM TrafficEvent t WHERE t.tenantId = :tenantId AND t.timestamp >= :since")
    long countEventsSinceForTenant(@Param("tenantId") String tenantId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(t) FROM TrafficEvent t WHERE t.tenantId = :tenantId AND t.status = :status AND t.timestamp >= :since")
    long countByStatusSinceForTenant(@Param("tenantId") String tenantId,
                                     @Param("status") String status,
                                     @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(AVG(t.latencyMs), 0) FROM TrafficEvent t WHERE t.tenantId = :tenantId AND t.timestamp >= :since")
    double getAverageLatencySinceForTenant(@Param("tenantId") String tenantId, @Param("since") LocalDateTime since);
}
