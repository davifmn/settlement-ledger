package io.github.davifmn.ledger.common;

import io.github.davifmn.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limit per participant, in Redis (INCR + EXPIRE). It protects the
 * service, not the money, so it fails open: if Redis is down, requests are let through.
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final LedgerProperties props;
    private final Clock clock;
    private final Counter rejected;

    public RateLimiter(StringRedisTemplate redis, LedgerProperties props, MeterRegistry meters) {
        this(redis, props, meters, Clock.systemUTC());
    }

    RateLimiter(StringRedisTemplate redis, LedgerProperties props, MeterRegistry meters, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
        this.rejected = Counter.builder("ledger.ratelimit.rejected").register(meters);
    }

    public void check(String participantId) {
        int limit = props.rateLimit().requestsPerMinute();
        if (limit <= 0) {
            return;
        }
        long second = clock.instant().getEpochSecond();
        String key = "rl:" + participantId + ":" + second / 60;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(120));
            }
        } catch (DataAccessException e) {
            return;
        }
        if (count != null && count > limit) {
            rejected.increment();
            throw ApiException.tooManyRequests(
                    "more than " + limit + " requests per minute for participant " + participantId, 60 - second % 60);
        }
    }
}
