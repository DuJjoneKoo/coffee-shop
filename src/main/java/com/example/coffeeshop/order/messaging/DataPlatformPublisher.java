package com.example.coffeeshop.order.messaging;

/**
 * 주문 내역을 외부 데이터 수집 플랫폼으로 전송하는 추상화.
 *
 * <p>구현체 후보:
 * <ul>
 *   <li>{@code MockDataPlatformPublisher}: 로깅/테스트용. 현재 default.</li>
 *   <li>{@code KafkaDataPlatformPublisher}: 운영용. 카프카 토픽 발행.</li>
 *   <li>{@code HttpDataPlatformPublisher}: REST 호출형 외부 시스템.</li>
 * </ul>
 *
 * <p><b>중요한 설계 포인트:</b> 발행은 <i>트랜잭션 커밋 이후</i> 일어나야 한다.
 * 이유: 주문 트랜잭션이 롤백되었는데 외부 시스템에는 이미 이벤트가 나가버리면 데이터 불일치.
 * 해결: Spring 의 {@link org.springframework.transaction.event.TransactionalEventListener} 와
 * {@code AFTER_COMMIT} 페이즈 활용.
 *
 * <p>그렇지만 단순 AFTER_COMMIT 발행은 "발행 직전 서버가 죽으면 이벤트 유실" 문제가 남는다.
 * 실무적인 강건한 선택지는 <b>Transactional Outbox 패턴</b>:
 * 같은 트랜잭션에서 outbox 테이블에 기록 -> 별도 워커가 polling 하여 발행.
 * 본 과제 범위에서는 AFTER_COMMIT 으로 시작하되, README 에 trade-off 를 기록.
 */
public interface DataPlatformPublisher {

    void publish(OrderCreatedEvent event);
}
