package com.ratelimiter.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "rate_limit_rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "tier"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String tier;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "request_limit")
    private int requestLimit;

    @Column(name = "window_ms")
    private long windowMs;

    @Column(name = "burst_capacity")
    private int burstCapacity;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
