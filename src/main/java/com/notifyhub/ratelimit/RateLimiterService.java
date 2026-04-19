package com.notifyhub.ratelimit;

import com.notifyhub.exception.RateLimitException;
import com.notifyhub.tenant.Tenant;
import com.notifyhub.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final TenantRepository tenantRepository;

    @Value("${app.rate-limit.default-limit}")
    private int defaultLimit;

    @Value("${app.rate-limit.window-seconds}")
    private int defaultWindowSeconds;

    private final Map<String, TenantLimit> tenantLimitCache = new ConcurrentHashMap<>();

    /**
     * Atomically increment the tenant's request counter in Redis. The first call in a window
     * sets the TTL; subsequent calls only increment the counter. This avoids the read-then-write
     * race that would otherwise let bursts slip past the limit.
     */
    public void checkRateLimit(String tenantId) {
        TenantLimit limit = tenantLimitCache.computeIfAbsent(tenantId, this::loadLimit);
        String key = "rate:" + tenantId;

        Long current = redisTemplate.opsForValue().increment(key);
        if (current == null) {
            log.warn("Redis INCR returned null for key {} — allowing request", key);
            return;
        }
        if (current == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(limit.windowSeconds()));
        }
        if (current > limit.limit()) {
            Long ttl = redisTemplate.getExpire(key);
            long retryAfter = (ttl == null || ttl < 0) ? limit.windowSeconds() : ttl;
            String msg = String.format(
                    "Tenant %s exceeded rate limit of %d per %d seconds",
                    tenantId, limit.limit(), limit.windowSeconds());
            throw new RateLimitException(msg, retryAfter);
        }
    }

    public void invalidateTenantCache(String tenantId) {
        tenantLimitCache.remove(tenantId);
    }

    private TenantLimit loadLimit(String tenantId) {
        return tenantRepository.findByTenantId(tenantId)
                .map(t -> new TenantLimit(nullSafe(t.getRateLimit(), defaultLimit),
                        nullSafe(t.getRateWindowSec(), defaultWindowSeconds)))
                .orElseGet(() -> {
                    log.debug("No tenant config for {} — using defaults", tenantId);
                    return new TenantLimit(defaultLimit, defaultWindowSeconds);
                });
    }

    private static int nullSafe(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private record TenantLimit(int limit, int windowSeconds) { }
}
