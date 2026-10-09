package com.ratelimiter.admin.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    @DisplayName("hash is lowercase hex SHA-256, as the migration computes in SQL")
    void hashMatchesSha256() {
        // Independently: printf %s api_key_beta_456 | sha256sum
        assertThat(ApiKeys.hash("api_key_beta_456"))
                .isEqualTo("4b38cda1235d10401f3243b87587f3002804a962eace5b9be6cd5bcbab6a932e");
    }

    @Test
    @DisplayName("generated keys are long, random and prefixed rk_")
    void generatedKeys() {
        String a = ApiKeys.generate();
        String b = ApiKeys.generate();

        assertThat(a).startsWith("rk_").hasSize(46).isNotEqualTo(b);
        assertThat(ApiKeys.prefix(a)).hasSize(12).isEqualTo(a.substring(0, 12));
    }
}
