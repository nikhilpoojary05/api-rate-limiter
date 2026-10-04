package com.ratelimiter.admin.service;

import com.ratelimiter.admin.dto.TimeSeriesPointDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrafficEventTimeSeriesTest {

    @Test
    void fillsEveryBucketInOrderWithZerosWhereNothingHappened() {
        List<Object[]> rows = List.of(
                new Object[]{101L, 7L, 2L, 12.5},
                new Object[]{103L, 1L, 0L, 40.0});

        List<TimeSeriesPointDto> points = TrafficEventService.fillBuckets(rows, 100, 104, 60);

        assertThat(points).hasSize(5);
        assertThat(points).extracting(TimeSeriesPointDto::getAllowed).containsExactly(0L, 7L, 0L, 1L, 0L);
        assertThat(points).extracting(TimeSeriesPointDto::getBlocked).containsExactly(0L, 2L, 0L, 0L, 0L);
        assertThat(points).extracting(TimeSeriesPointDto::getAvgLatency).containsExactly(0.0, 12.5, 0.0, 40.0, 0.0);
    }

    @Test
    void bucketTimestampIsTheBucketStartInTheZoneEventsAreStoredIn() {
        List<TimeSeriesPointDto> points = TrafficEventService.fillBuckets(List.of(), 100, 100, 60);

        LocalDateTime wallClock = LocalDateTime.ofEpochSecond(100 * 60, 0, ZoneOffset.UTC);
        assertThat(points.get(0).getTimestamp())
                .isEqualTo(wallClock.atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void acceptsTheDashboardRangesAndRejectsAnythingElse() {
        assertThat(TrafficEventService.Range.parse("1h").bucket.getSeconds()).isEqualTo(60);
        assertThat(TrafficEventService.Range.parse("7d").window.toDays()).isEqualTo(7);
        assertThatThrownBy(() -> TrafficEventService.Range.parse("90d"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
