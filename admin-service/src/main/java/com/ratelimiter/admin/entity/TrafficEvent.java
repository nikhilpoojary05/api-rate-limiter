package com.ratelimiter.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "traffic_events", indexes = {
        @Index(name = "idx_traffic_tenant_ts", columnList = "tenant_id, timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "ip_address")
    private String ipAddress;

    private String path;

    private String method;

    private String status;

    @Column(name = "latency_ms")
    private long latencyMs;

    @Column(name = "http_status")
    private int httpStatus;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
