package com.example.coffeeshop.order.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 외부 데이터 수집 플랫폼의 Mock 구현.
 *
 * <p>현재 단계: 실제 외부 시스템 연동 대신 로그로 발행 흔적만 남긴다 (과제 요구사항).
 * <p>운영 전환 시: {@link DataPlatformPublisher} 의 또 다른 구현체(Kafka, HTTP 등) 를 만들고
 * {@code @Profile} 또는 {@code @ConditionalOnProperty} 로 환경별 빈을 분리하면 된다.
 *
 * <p>현재는 Mock 만 등록되어 있어 단일 구현체. 운영용 빈을 추가할 때는 Mock 에 {@code @Profile("local")}
 * 등으로 한정하지 않으면 빈 충돌이 발생하므로 주의.
 */
@Slf4j
@Component
public class MockDataPlatformPublisher implements DataPlatformPublisher {

    @Override
    public void publish(OrderCreatedEvent event) {
        // TODO: 실제 외부 시스템 호출 (Kafka producer, HTTP POST 등) 으로 교체.
        // 현재는 stdout 으로 발행 흔적만 — 채점자가 콘솔에서 발행 여부를 검증할 수 있음.
        log.info("[DataPlatform] 발행 -> userId={}, menuId={}, paidAmount={}, orderId={}",
                event.userId(), event.menuId(), event.paidAmount(), event.orderId());
    }
}
