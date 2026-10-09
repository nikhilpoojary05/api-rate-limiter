package com.ratelimiter.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of a caller's limit each request uses, by path prefix. Most requests cost 1;
 * an expensive endpoint can cost more, so a tenant allowed 100 per minute gets 100 cheap
 * requests or 20 at a cost of 5. Configured under {@code ratelimit.costs}; the longest
 * matching prefix wins.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit")
public class EndpointCosts {

    private Map<String, Integer> costs = new LinkedHashMap<>();

    public Map<String, Integer> getCosts() {
        return costs;
    }

    public void setCosts(Map<String, Integer> costs) {
        costs.forEach((prefix, cost) -> {
            if (cost == null || cost < 1) {
                throw new IllegalArgumentException("ratelimit.costs: cost for " + prefix + " must be at least 1");
            }
        });
        this.costs = new LinkedHashMap<>(costs);
    }

    public int costOf(String path) {
        int cost = 1;
        int longest = -1;
        for (Map.Entry<String, Integer> entry : costs.entrySet()) {
            String prefix = entry.getKey();
            if (path.startsWith(prefix) && prefix.length() > longest) {
                longest = prefix.length();
                cost = entry.getValue();
            }
        }
        return cost;
    }
}
