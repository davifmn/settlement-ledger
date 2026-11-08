package io.github.davifmn.ledger.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key handling in Redis: the fast path (docs/adr/0005).
 *
 * <p>The first request with a key stores "in progress"; a concurrent duplicate gets 409,
 * a later one gets the original result. Redis can lose a key (eviction, failover) or be
 * down; then the request goes through and the database's unique constraint on
 * (participant, idempotency key) catches the duplicate. Redis only makes the common
 * case cheap: it never decides alone.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    static final Duration TTL = Duration.ofHours(24);

    public sealed interface Decision {

        /** Go ahead. {@code tracked} is false when Redis was unavailable. */
        record Proceed(boolean tracked) implements Decision {
        }

        /** Already done: return the resource created by the first request. */
        record Replay(String resourceId) implements Decision {
        }
    }

    private final StringRedisTemplate redis;
    private final Counter unavailable;

    public IdempotencyService(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.unavailable = Counter.builder("ledger.idempotency.redis.unavailable").register(meters);
    }

    public Decision begin(String scope, String key, String requestHash) {
        String redisKey = redisKey(scope, key);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, "P|" + requestHash, TTL))) {
                    return new Decision.Proceed(true);
                }
                String stored = redis.opsForValue().get(redisKey);
                if (stored == null) {
                    continue; // expired between the two calls: try to claim it again
                }
                String[] parts = stored.split("\\|", 3);
                if (!parts[1].equals(requestHash)) {
                    throw ApiException.unprocessable("this Idempotency-Key was already used for a different request");
                }
                if (parts[0].equals("P")) {
                    throw ApiException.conflict("a request with this Idempotency-Key is still being processed");
                }
                return new Decision.Replay(parts[2]);
            }
            throw ApiException.conflict("could not claim the Idempotency-Key, retry");
        } catch (DataAccessException e) {
            unavailable.increment();
            log.warn("Redis unavailable, relying on the database for idempotency: {}", e.getMessage());
            return new Decision.Proceed(false);
        }
    }

    public void complete(String scope, String key, String requestHash, String resourceId) {
        try {
            redis.opsForValue().set(redisKey(scope, key), "D|" + requestHash + "|" + resourceId, TTL);
        } catch (DataAccessException e) {
            unavailable.increment();
        }
    }

    /** The request failed: forget the key so that the client can retry with it. */
    public void abandon(String scope, String key) {
        try {
            redis.delete(redisKey(scope, key));
        } catch (DataAccessException e) {
            unavailable.increment();
        }
    }

    private static String redisKey(String scope, String key) {
        return "idem:" + scope + ":" + key;
    }
}
