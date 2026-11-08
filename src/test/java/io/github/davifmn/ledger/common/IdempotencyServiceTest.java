package io.github.davifmn.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

class IdempotencyServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry meters;
    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        meters = new SimpleMeterRegistry();
        service = new IdempotencyService(redis, meters);
    }

    @Test
    void theFirstRequestClaimsTheKey() {
        when(values.setIfAbsent(eq("idem:alice:key-1"), eq("P|h1"), any(Duration.class))).thenReturn(true);

        assertThat(service.begin("alice", "key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Proceed(true));
    }

    @Test
    void aFinishedRequestIsReplayed() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get("idem:alice:key-1")).thenReturn("D|h1|transfer-42");

        assertThat(service.begin("alice", "key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Replay("transfer-42"));
    }

    @Test
    void aConcurrentDuplicateIsAConflict() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get("idem:alice:key-1")).thenReturn("P|h1");

        assertThatThrownBy(() -> service.begin("alice", "key-1", "h1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get("idem:alice:key-1")).thenReturn("D|h1|transfer-42");

        assertThatThrownBy(() -> service.begin("alice", "key-1", "h2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void withoutRedisTheRequestProceedsAndTheDatabaseDecides() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(service.begin("alice", "key-1", "h1")).isEqualTo(new IdempotencyService.Decision.Proceed(false));
        assertThat(meters.counter("ledger.idempotency.redis.unavailable").count()).isEqualTo(1);
    }

    @Test
    void completeStoresTheResult() {
        service.complete("alice", "key-1", "h1", "transfer-42");

        verify(values).set("idem:alice:key-1", "D|h1|transfer-42", IdempotencyService.TTL);
    }
}
