package com.example.coffeeshop.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 작업용 스레드 풀 설정.
 *
 * <p>도입 배경 (2026-05-11 — 리뷰 블로커 B1):
 * <ul>
 *   <li>분산 락이 {@code OrderService.order()} 트랜잭션 전체를 감싸도록 1순위에서 수정됨.</li>
 *   <li>그 결과 {@code @TransactionalEventListener(AFTER_COMMIT)} 의 외부 발행이 락 보유 시간 안에 동기 실행됨.</li>
 *   <li>외부 시스템(Kafka/HTTP) 응답 지연이 그대로 락 보유 시간을 잠식 → 동일 사용자 후속 요청 차단.</li>
 * </ul>
 *
 * <p>해결: 외부 발행을 {@code @Async("dataPlatformExecutor")} 로 분리된 스레드 풀로 위임하여
 * 트랜잭션 커밋 직후 락이 즉시 해제되도록 한다. 외부 I/O 지연은 별도 풀에서만 흡수됨.
 *
 * <p>스레드 풀 사이징 근거:
 * <ul>
 *   <li>core/max = 4 / 16 — 외부 시스템 호출이 I/O bound 라 코어 수 × 4 정도가 합리적.</li>
 *   <li>queueCapacity = 500 — burst 흡수용. 가득 차면 CallerRunsPolicy 로 *호출 스레드가 직접 실행* →
 *       메인 요청 스레드가 잠시 늦어지는 한이 있어도 메시지 유실은 막는다.</li>
 *   <li>너무 큰 풀/큐는 외부 시스템 장애 시 메모리 폭증 위험.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 외부 데이터 플랫폼 발행 전용 스레드 풀.
     *
     * <p>주의: 한 도메인 한 풀 원칙. 다른 비동기 작업(예: 알림, 이메일) 이 추가되면 별도 풀을 만들어
     * 한 도메인의 외부 시스템 장애가 다른 도메인 처리량을 잠식하지 않도록 분리한다.
     */
    @Bean("dataPlatformExecutor")
    public Executor dataPlatformExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("data-platform-");
        // 큐가 가득 차면 호출 스레드(=리스너 스레드)가 직접 실행 → 메시지 유실보다 호출 지연을 택함.
        // 어차피 AFTER_COMMIT 리스너 스레드는 락 밖이므로 잠시 늦어도 시스템 영향 적음.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("dataPlatformExecutor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);
        return executor;
    }
}
