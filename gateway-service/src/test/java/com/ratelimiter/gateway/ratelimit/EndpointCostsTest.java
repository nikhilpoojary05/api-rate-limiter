package com.ratelimiter.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointCostsTest {

    @Test
    @DisplayName("unlisted paths cost 1, and the longest matching prefix wins")
    void longestPrefixWins() {
        EndpointCosts costs = new EndpointCosts();
        costs.setCosts(Map.of("/api/demo/", 2, "/api/demo/slow", 5));

        assertThat(costs.costOf("/api/auth/login")).isEqualTo(1);
        assertThat(costs.costOf("/api/demo/ping")).isEqualTo(2);
        assertThat(costs.costOf("/api/demo/slow")).isEqualTo(5);
    }

    @Test
    @DisplayName("a cost below 1 is a configuration error")
    void rejectsCostBelowOne() {
        assertThatThrownBy(() -> new EndpointCosts().setCosts(Map.of("/api/demo/slow", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
