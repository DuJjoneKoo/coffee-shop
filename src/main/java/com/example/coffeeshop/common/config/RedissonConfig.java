package com.example.coffeeshop.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 설정.
 *
 * <p>왜 Redisson인가?
 * <ul>
 *   <li>Lettuce SETNX + 폴링 방식은 락 대기 중 CPU/네트워크를 낭비. Redisson은 pub/sub으로 락 해제 알림을 받아 효율적.</li>
 *   <li>RLock 은 watch dog 으로 TTL 자동 연장 -> 처리 시간이 락 TTL 초과해도 안전.</li>
 *   <li>ReentrantLock 시맨틱 지원: 같은 스레드가 같은 락을 중첩 획득 가능.</li>
 * </ul>
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10);
        return Redisson.create(config);
    }
}
