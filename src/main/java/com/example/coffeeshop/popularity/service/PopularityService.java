package com.example.coffeeshop.popularity.service;

import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.order.repository.OrderRepository;
import com.example.coffeeshop.popularity.dto.PopularMenuResponse;
import com.example.coffeeshop.popularity.dto.PopularMenuRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 인기 메뉴 조회 서비스.
 *
 * <p>요구사항: "최근 7일간 인기 메뉴 TOP 3" + "주문 횟수 정확성".
 *
 * <p>설계 메모:
 * <ul>
 *   <li>현재 구현은 매 호출마다 GROUP BY 집계 — 정확성 100%, 다만 주문량이 폭증하면 부담.</li>
 *   <li>다중 인스턴스 환경에서 캐시를 도입한다면 Redis 단일 소스를 권장 (인스턴스별 로컬 캐시는 일관성 깨짐).</li>
 *   <li>대안 1: {@code @Cacheable("popular-menus")} + Redis CacheManager + 짧은 TTL(예: 60초).
 *       정확성 trade-off (TTL 동안 stale 데이터 허용) 가 발생하므로 README 에 명시 필수.</li>
 *   <li>대안 2: 주문 발생 시 Redis ZSET 에 ZINCRBY → 조회 시 ZREVRANGE TOP 3.
 *       O(log N) 조회, 다만 7일 윈도우는 별도 expire 전략 필요.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 모든 메서드 기본 readOnly — dirty checking 비용 절감
public class PopularityService {

    /** 노출할 인기 메뉴 개수 (요구사항: 3개). */
    private static final int TOP_N = 3;

    /** 집계 기간 (요구사항: 최근 7일). */
    private static final int RECENT_DAYS = 7;

    private final OrderRepository orderRepository;
    private final MenuRepository menuRepository;

    /**
     * 최근 7일 인기 메뉴 TOP 3 조회.
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>주문 테이블에서 menuId 별 카운트 → DB 가 직접 정렬, TOP_N 만 반환.</li>
     *   <li>나온 menuId 들로 메뉴 정보(이름/가격) 일괄 조회 (N+1 방지: IN 쿼리).</li>
     *   <li>두 결과를 결합하여 응답 DTO 생성.</li>
     * </ol>
     */
    public List<PopularMenuResponse> getTopPopularMenus() {
        // 7일 전 시점을 호출 시점에 계산. (서버 시계 기준)
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);

        // 1. 주문 집계: TOP_N 만 가져옴 (LIMIT 은 Pageable 로 전달).
        //    DB 가 인덱스(idx_order_created_at, idx_order_menu_created) 를 활용해 정렬.
        List<PopularMenuRow> rows = orderRepository.findPopularMenus(since, PageRequest.of(0, TOP_N));
        if (rows.isEmpty()) {
            // 주문 자체가 없을 때 빈 배열 반환 — 클라이언트가 "신규 매장" UI 로 분기 가능.
            return List.of();
        }

        // 2. 메뉴 정보 일괄 조회 (N+1 방지: IN 쿼리 1회).
        //    rows 가 최대 3개라 영향이 작지만, TOP_N 이 커져도 안전한 패턴 유지.
        List<Long> menuIds = rows.stream().map(PopularMenuRow::menuId).toList();
        Map<Long, Menu> menuMap = menuRepository.findAllById(menuIds).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        // 3. 결합 — 집계 row 의 순서(주문 횟수 내림차순) 를 그대로 유지.
        return rows.stream()
                .map(row -> {
                    Menu menu = menuMap.get(row.menuId());
                    // 메뉴가 그 사이 삭제됐을 수도 있음 (soft delete 미적용 환경) → null 가드.
                    return new PopularMenuResponse(
                            row.menuId(),
                            menu != null ? menu.getName() : "(삭제된 메뉴)",
                            menu != null ? menu.getPrice() : 0L,
                            row.orderCount()
                    );
                })
                .toList();
    }
}
