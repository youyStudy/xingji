package com.xingji.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德接口结果的统一缓存。
 * 主存 Redis(多实例共享、重启不丢),同时在进程内留一份副本:Redis 未启动或连接失败时
 * 自动降级到本地副本,答辩机器不装 Redis 也能正常运行,不会因为缓存不可用而挡住主流程。
 * 只缓存幂等只读结果;调用失败不写缓存,避免把一次抖动固化下来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmapCache {

    /** Redis 掉线后间隔多久再试一次,避免每个请求都卡在连接超时上 */
    private static final long RETRY_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Map<String, Entry> local = new ConcurrentHashMap<>();
    private volatile long redisRetryAt = 0L;

    public <T> T get(String key, TypeReference<T> type) {
        String json = null;
        long localExpireAt = 0L;
        if (redisAvailable()) {
            try {
                json = redis.opsForValue().get(key);
            } catch (Exception e) {
                markRedisDown(e);
            }
        }
        if (json == null) {
            Entry entry = local.get(key);
            if (entry != null) {
                if (entry.expireAt() > System.currentTimeMillis()) {
                    json = entry.json();
                    localExpireAt = entry.expireAt();
                } else {
                    local.remove(key);
                }
            }
        }
        if (json == null) return null;
        // Redis 里没有、本地副本命中的情况(刚重启 Redis / 本地先写入),顺手回填 Redis,让多实例共享同一份结果
        if (localExpireAt > 0 && redisAvailable()) {
            long ttl = Math.max(1000L, localExpireAt - System.currentTimeMillis());
            try {
                redis.opsForValue().set(key, json, Duration.ofMillis(ttl));
            } catch (Exception e) {
                markRedisDown(e);
            }
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[缓存] 反序列化失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            local.put(key, new Entry(json, System.currentTimeMillis() + ttl.toMillis()));
            if (redisAvailable()) {
                try {
                    redis.opsForValue().set(key, json, ttl);
                } catch (Exception e) {
                    markRedisDown(e);
                }
            }
        } catch (Exception e) {
            log.warn("[缓存] 序列化失败 key={}: {}", key, e.getMessage());
        }
    }

    private boolean redisAvailable() {
        return System.currentTimeMillis() >= redisRetryAt;
    }

    private void markRedisDown(Exception e) {
        redisRetryAt = System.currentTimeMillis() + RETRY_MS;
        log.warn("[缓存] Redis 不可用,降级为进程内缓存: {}", e.getMessage());
    }

    private record Entry(String json, long expireAt) {
    }
}
