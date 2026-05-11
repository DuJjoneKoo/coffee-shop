package com.example.coffeeshop.order.repository;

import com.example.coffeeshop.order.domain.Order;
import com.example.coffeeshop.popularity.dto.PopularMenuRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 인기 메뉴 집계 쿼리 (최근 N일 윈도우 내 menuId 별 주문 횟수 내림차순).
     *
     * <p>실행 계획 가정:
     * <ul>
     *   <li>{@code idx_order_menu_created (menuId, createdAt)} 인덱스로 GROUP BY + WHERE 가속.</li>
     *   <li>{@code idx_order_created_at} 단독 인덱스도 7일 윈도우 필터에 도움.</li>
     * </ul>
     *
     * <p>주의: 주문량이 폭증하면 이 쿼리도 부담. 대안:
     * <ul>
     *   <li>Redis Sorted Set 으로 실시간 집계 (ZINCRBY) → O(log N) 조회.</li>
     *   <li>일배치로 popularity_summary 테이블에 사전 집계 → 조회는 단순 SELECT TOP 3.</li>
     *   <li>Materialized View (DB 지원 시).</li>
     * </ul>
     * 현재는 주문 데이터가 단일 source-of-truth 이고, 인덱스로 충분히 빠를 것이라 가정.
     *
     * <p>[2026-05-11 수정] 결정성 정렬 보조 키 추가: {@code ORDER BY COUNT(o) DESC, o.menuId ASC}.
     * 사유: SA 요구사항 "메뉴별 주문 횟수가 정확해야 합니다" 는 *재현성/결정성* 도 포함.
     * 동률 시 어느 메뉴가 TOP 3 안에 들지 비결정적이면 같은 입력에 대해 다른 결과가 나올 수 있어
     * "정확하다" 고 말하기 어려움. menuId 오름차순을 보조 키로 추가하여 결정성 확보.
     *
     * @param since    집계 시작 시각 (포함)
     * @param pageable LIMIT / OFFSET — TOP N 제한용
     */
    @Query("""
        SELECT new com.example.coffeeshop.popularity.dto.PopularMenuRow(o.menuId, COUNT(o))
        FROM Order o
        WHERE o.createdAt >= :since
        GROUP BY o.menuId
        ORDER BY COUNT(o) DESC, o.menuId ASC
    """)
    List<PopularMenuRow> findPopularMenus(@Param("since") LocalDateTime since, Pageable pageable);
}
