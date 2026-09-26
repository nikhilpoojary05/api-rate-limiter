package com.ratelimiter.auth.repository;

import com.ratelimiter.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByTenantId(UUID tenantId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}