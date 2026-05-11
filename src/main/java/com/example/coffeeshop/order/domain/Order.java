package com.example.coffeeshop.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 엔티티 — 결제까지 완료된 단일 주문 한 건을 표현.
 *
 * <p>설계 메모:
 * <ul>
 *   <li>한 주문 = 한 메뉴 — 과제 요구사항 단순화. 멀티 아이템 주문이 필요하면 OrderLine 분리.</li>
 *   <li>{@code paidAmount} 는 결제 시점의 메뉴 가격 스냅샷. 메뉴 가격이 후일 변경돼도 주문 금액은 불변.</li>
 *   <li>userId / menuId 는 외래키 대신 식별값만 보관 — 도메인 결합도를 낮추고 인덱스 비용 최소화.</li>
 * </ul>
 *
 * <p>인덱스 전략:
 * <ul>
 *   <li>{@code idx_order_user} — 사용자별 주문 이력 조회용.</li>
 *   <li>{@code idx_order_created_at} — 인기 메뉴 집계의 7일 윈도우 필터({@code WHERE created_at >= ?}) 가속.</li>
 *   <li>{@code idx_order_menu_created} (menuId, createdAt) — 인기 메뉴 GROUP BY 집계 가속.</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_order_user", columnList = "userId"),
        // 인기 메뉴 집계: WHERE created_at >= ? 로 최근 7일 필터하므로 created_at 인덱스 필수
        @Index(name = "idx_order_created_at", columnList = "createdAt"),
        @Index(name = "idx_order_menu_created", columnList = "menuId, createdAt")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 기본 생성자. 외부에서 직접 호출 차단.
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문한 사용자 식별값. (외래키 없이 식별값만 보관) */
    @Column(nullable = false)
    private Long userId;

    /** 주문된 메뉴 ID. */
    @Column(nullable = false)
    private Long menuId;

    /**
     * 결제 금액(원) — 주문 시점의 메뉴 가격 스냅샷.
     * 이후 메뉴 가격이 인상되더라도 이 주문의 금액은 그대로 유지되어야 함.
     */
    @Column(nullable = false)
    private long paidAmount;

    /**
     * 주문 생성 시각.
     *
     * <p>현재는 생성자에서 {@code LocalDateTime.now()} 직접 호출 — 테스트 시 시간 제어가 어려움.
     * 개선안: {@code @PrePersist} 또는 Spring Data JPA Auditing(@CreatedDate) 으로 외부화.
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Order(Long userId, Long menuId, long paidAmount) {
        this.userId = userId;
        this.menuId = menuId;
        this.paidAmount = paidAmount;
        // 생성 시점에 즉시 기록. JPA 영속화 전이라도 이 시각이 곧 "주문 발생 시각" 으로 의미가 있음.
        this.createdAt = LocalDateTime.now();
    }
}
