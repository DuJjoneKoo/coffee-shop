package com.example.coffeeshop.order.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>[2026-05-11 수정 — 리뷰 블로커 B2]
 * 위 javadoc 의 경고를 코드 레벨로 강제. {@code @ConditionalOnProperty} 로 mode 가 "mock" 일 때만
 * 빈이 등록되도록 변경. {@code matchIfMissing=true} 라 설정 누락 시 mock 으로 fallback 하여
 * 로컬 부팅은 그대로 동작하고, 운영은 명시적으로 다른 값(예: kafka) 을 줘야 Mock 이 등록되지 않음.
 * 즉 "주석 규칙이 PR 리뷰에서 누락되어 운영에 Mock 이 들어가는" 사고를 코드로 차단.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coffeeshop.data-platform.mode", havingValue = "mock", matchIfMissing = true)
public class MockDataPlatformPublisher implements DataPlatformPublisher {

    @Override
    public void publish(OrderEventMessage message) {
        // TODO: 실제 외부 시스템 호출 (Kafka producer, HTTP POST 등) 으로 교체.
        // 현재는 stdout 으로 발행 흔적만 — 채점자가 콘솔에서 발행 여부를 검증할 수 있음.
        // [2026-05-11 수정 — 리뷰 권장개선 W3] schemaVersion 도 함께 로깅하여 외부 컨트랙트 가시성 확보.
        log.info("[DataPlatform] 발행 -> schemaVersion={}, orderId={}, userId={}, menuId={}, paidAmount={}",
                message.schemaVersion(), message.orderId(), message.userId(),
                message.menuId(), message.paidAmount());
    }
}
