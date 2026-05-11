package com.example.coffeeshop.order.messaging;

import java.time.LocalDateTime;

/**
 * 외부 데이터 플랫폼으로 전송되는 메시지 페이로드.
 *
 * <p>도입 배경 (2026-05-11 — 리뷰 권장개선 W3):
 * <ul>
 *   <li>내부 도메인 이벤트 {@link OrderCreatedEvent} 와 외부 컨트랙트의 라이프사이클이 다름.</li>
 *   <li>내부 이벤트는 리팩토링으로 자유롭게 바뀌어야 하지만, 외부 컨트랙트는 한번 발행되면 깨기 어려움.</li>
 *   <li>둘을 같은 클래스로 묶으면 "이벤트 필드 추가" 가 외부 시스템 회의 안건이 됨.</li>
 * </ul>
 *
 * <p>분리 원칙:
 * <ul>
 *   <li>내부 이벤트: {@link OrderCreatedEvent} — 자유롭게 변경 가능.</li>
 *   <li>외부 메시지: 본 클래스 — {@code schemaVersion} 기반 호환성 관리.
 *       컨슈머가 마이그레이션 hook 으로 사용할 수 있음.</li>
 *   <li>변환: {@link #from(OrderCreatedEvent)} 매퍼 한 곳에서만 — 결합도 명시화.</li>
 * </ul>
 *
 * @param schemaVersion 메시지 스키마 버전. 외부 컨슈머가 분기 처리할 수 있는 식별자.
 *                      필드 추가 시에는 minor (v1 → v1.1), 호환성 깨지는 변경은 major (v1 → v2).
 * @param orderId       주문 PK
 * @param userId        주문한 사용자 식별값
 * @param menuId        주문된 메뉴 ID
 * @param paidAmount    결제 금액(원)
 * @param occurredAt    주문 발생 시각
 */
public record OrderEventMessage(
        String schemaVersion,
        Long orderId,
        Long userId,
        Long menuId,
        long paidAmount,
        LocalDateTime occurredAt
) {

    /** 현재 메시지 스키마 버전. 호환성 깨지는 변경 시에만 bump. */
    public static final String CURRENT_SCHEMA_VERSION = "v1";

    /**
     * 도메인 이벤트 → 외부 메시지 변환.
     * 변환 지점이 한 곳이라 향후 필드 추가/변경 시 영향 범위가 명확.
     */
    public static OrderEventMessage from(OrderCreatedEvent event) {
        return new OrderEventMessage(
                CURRENT_SCHEMA_VERSION,
                event.orderId(),
                event.userId(),
                event.menuId(),
                event.paidAmount(),
                event.occurredAt()
        );
    }
}
