package io.github.davifmn.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.davifmn.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RateLimiterTest {

    // 45 s into the minute: 15 s remain in the window
    private static final Instant NOW = Instant.parse("2025-10-10T10:00:45Z");

    private ValueOperations<String, String> values;
    private RateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var props = new LedgerProperties(2, null, null, null, new LedgerProperties.RateLimit(3), null);
        limiter = new RateLimiter(redis, props, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allowsUpToTheLimitThenTellsWhenToRetry() {
        String key = "rl:alice:" + NOW.getEpochSecond() / 60;
        when(values.increment(key)).thenReturn(3L, 4L);

        assertThatCode(() -> limiter.check("alice")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("alice"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(15));
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(values.increment("rl:alice:" + NOW.getEpochSecond() / 60))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.check("alice")).doesNotThrowAnyException();
    }
}
