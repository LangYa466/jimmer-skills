package com.example.runtime

import org.babyfish.jimmer.jackson.codec.JsonCodec
import org.babyfish.jimmer.meta.ImmutableProp
import org.babyfish.jimmer.meta.ImmutableType
import org.babyfish.jimmer.spring.cache.RedisCacheCreator
import org.babyfish.jimmer.sql.cache.Cache
import org.babyfish.jimmer.sql.cache.CacheFactory
import org.babyfish.jimmer.sql.kt.cache.AbstractKCacheFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.time.Duration

@Profile("redis")                                           // compile check only
@Configuration
class RedisCacheConfig {

    @Bean
    fun cacheFactory(connectionFactory: RedisConnectionFactory): CacheFactory {
        val creator = RedisCacheCreator(connectionFactory, JsonCodec.jsonCodec())
            .withRemoteDuration(Duration.ofHours(1))
            .withLocalCache(100, Duration.ofMinutes(5))
        return object : AbstractKCacheFactory() {
            override fun createObjectCache(type: ImmutableType): Cache<*, *>? =
                creator.createForObject<Any, Any>(type)
            override fun createAssociatedIdCache(prop: ImmutableProp): Cache<*, *>? =
                creator.createForProp<Any, Any>(prop, filterState.isAffected(prop.targetType))
            override fun createAssociatedIdListCache(prop: ImmutableProp): Cache<*, List<*>>? =
                creator.createForProp<Any, List<*>>(prop, filterState.isAffected(prop.targetType))
            override fun createResolverCache(prop: ImmutableProp): Cache<*, *>? =
                creator.createForProp<Any, Any>(prop, true)
        }
    }
}
