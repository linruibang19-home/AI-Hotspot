package com.aihotspot.core.auth;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean allowed(String namespace, String key, int maximum, Duration window) {
        String redisKey = "ai-hotspot:rate:" + namespace + ":" + Integer.toHexString(key.hashCode());
        Long count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1) redis.expire(redisKey, window);
        return count == null || count <= maximum;
    }

    public void clear(String namespace, String key) {
        redis.delete("ai-hotspot:rate:" + namespace + ":" + Integer.toHexString(key.hashCode()));
    }
}
