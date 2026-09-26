    package com.ratelimiter.auth.entity;

    import jakarta.persistence.*;
    import lombok.AllArgsConstructor;
    import lombok.Builder;
    import lombok.Data;
    import lombok.NoArgsConstructor;
    import org.hibernate.annotations.CreationTimestamp;
    import org.hibernate.annotations.UpdateTimestamp;

    import java.time.LocalDateTime;
    import java.util.List;
    import java.util.UUID;      

    @Entity
    @Table(name = "tenants")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public class Tenant {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(unique = true, nullable = false)
        private String name;

        @Column(nullable = false)
        private String tier;

        private String description;

        @Builder.Default
        private boolean active = true;

        @CreationTimestamp
        @Column(name = "created_at", updatable = false)
        private LocalDateTime createdAt;

        @UpdateTimestamp
        @Column(name = "updated_at")
        private LocalDateTime updatedAt;

        @OneToMany(mappedBy = "tenant")
        private List<AppUser> users;
    }
