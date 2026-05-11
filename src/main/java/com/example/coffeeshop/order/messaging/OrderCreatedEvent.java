package com.example.coffeeshop.order.messaging;

import java.time.LocalDateTime;

/**
 * 주문 생성 도메인 이벤트.
 *
 * <p>역할:
 * <ul>
 *   <li>Spring 의 {@code ApplicationEventPublisher} 로 발행되어
 *       {@link OrderEventListener} 가 트랜잭션 커밋 후 수신.</li>
 *   <li>외부 데이터 수집 플랫폼 전송 페이로드(payload) 와 동일 구조.</li>
 * </ul>
 *
 * <p>{@code record} 로 정의 — 불변 객체이며 equals/hashCode/toString 자동 생성.
 * 이벤트 객체는 절대 변형되어선 안 되므로 record 가 적합.
 *
 * @param orderId    주문 PK
 * @param userId     주문한 사용자 식별값
 * @param menuId     주문된 메뉴 ID
 * @param paidAmount 결제 금액(원)
 * @param occurredAt 주문 발생 시각 — 외부 시스템에서 시계열 분석에 활용
 */
public record OrderCreatedEvent(
        Long orderId,
        Long userId,
        Long menuId,
        long paidAmount,
        LocalDateTime occurredAt
) {
}
