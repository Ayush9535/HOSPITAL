package com.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * RedisConfig - Configuration to enable Spring Boot caching support.
 *
 * Implements CachingConfigurer (rather than exposing a standalone
 * CacheErrorHandler @Bean) because plain @EnableCaching only wires a custom
 * error handler through this interface — an unattached bean of that type is
 * silently ignored and Spring falls back to its default handler, which
 * rethrows and turns a Redis outage into a 500 on every cached endpoint.
 */
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
    }

    /**
     * Caching here is a best-effort performance optimization (stats/dashboard
     * lookups), never a data source of truth — the annotated methods still
     * compute the real result on a cache miss. The default Spring behavior
     * lets a Redis outage propagate as a 500 out of @Cacheable/@CacheEvict
     * methods, turning an optional speedup into a hard dependency. Log and
     * swallow instead, so the request falls through to the real method body.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                logger.warn("Cache GET failed for cache '{}' key '{}' — falling back to source", cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                logger.warn("Cache PUT failed for cache '{}' key '{}'", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                logger.warn("Cache EVICT failed for cache '{}' key '{}'", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                logger.warn("Cache CLEAR failed for cache '{}'", cache.getName(), exception);
            }
        };
    }
}
