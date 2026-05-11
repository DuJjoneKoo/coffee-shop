package com.example.coffeeshop.popularity;

import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.order.domain.Order;
import com.example.coffeeshop.order.repository.OrderRepository;
import com.example.coffeeshop.popularity.dto.PopularMenuResponse;
import com.example.coffeeshop.popularity.service.PopularityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 메뉴 조회 정확성 검증.
 *
 * <p>SA 요구사항 매핑:
 * <ul>
 *   <li>"최근 7일간 인기 메뉴 3개" → TOP 3 + 7일 윈도우 필터.</li>
 *   <li>"메뉴별 주문 횟수가 정확해야 합니다" → 카운트 정확성 + 동률 시 결정성 정렬.</li>
 * </ul>
 *
 * <p>Order 엔티티의 {@code createdAt} 이 생성자에서 {@code now()} 로 고정이라 과거 시점 주문은
 * Reflection 으로 강제 주입. 운영 코드에서는 {@code @CreatedDate} 또는 Clock 주입으로
 * 외부화하는 게 정공법이지만, 본 테스트는 *시점 제어가 핵심*이라 한정적으로 사용.
 */
@SpringBootTest
class PopularityServiceTest {

    @Autowired
    private PopularityService popularityService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private MenuRepository menuRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        menuRepository.deleteAll();
    }

    @Test
    @DisplayName("주문이 없으면 빈 리스트를 반환한다")
    void emptyWhenNoOrders() {
        // when
        List<PopularMenuResponse> result = popularityService.getTopPopularMenus();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("주문 횟수 내림차순으로 TOP 3 만 반환한다")
    void topThreeOrderedByCount() {
        // given: 메뉴 5종 + 메뉴별 다른 주문 횟수
        Menu americano   = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build()); // 3건
        Menu latte       = menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());   // 5건 — 1위
        Menu cappuccino  = menuRepository.save(Menu.builder().name("카푸치노").price(5000).build());   // 2건
        Menu vanilla     = menuRepository.save(Menu.builder().name("바닐라라떼").price(5500).build()); // 4건 — 2위
        Menu mocha       = menuRepository.save(Menu.builder().name("카페모카").price(5500).build());   // 1건

        placeOrders(americano.getId(), 3);
        placeOrders(latte.getId(), 5);
        placeOrders(cappuccino.getId(), 2);
        placeOrders(vanilla.getId(), 4);
        placeOrders(mocha.getId(), 1);

        // when
        List<PopularMenuResponse> result = popularityService.getTopPopularMenus();

        // then: 정확히 3개, 5건 > 4건 > 3건 순서.
        assertThat(result).hasSize(3);
        assertThat(result.get(0).menuId()).isEqualTo(latte.getId());
        assertThat(result.get(0).orderCount()).isEqualTo(5L);
        assertThat(result.get(1).menuId()).isEqualTo(vanilla.getId());
        assertThat(result.get(1).orderCount()).isEqualTo(4L);
        assertThat(result.get(2).menuId()).isEqualTo(americano.getId());
        assertThat(result.get(2).orderCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("주문 횟수 동률 시 menuId 오름차순으로 결정성을 보장한다")
    void deterministicOrderOnTie() {
        // given: 4개 메뉴 모두 동일하게 2건씩.
        // menuId 오름차순으로 정렬되어야 하므로 PK 순서가 곧 결과 순서가 됨.
        Menu a = menuRepository.save(Menu.builder().name("A").price(1000).build());
        Menu b = menuRepository.save(Menu.builder().name("B").price(1000).build());
        Menu c = menuRepository.save(Menu.builder().name("C").price(1000).build());
        Menu d = menuRepository.save(Menu.builder().name("D").price(1000).build());

        placeOrders(a.getId(), 2);
        placeOrders(b.getId(), 2);
        placeOrders(c.getId(), 2);
        placeOrders(d.getId(), 2);

        // when
        List<PopularMenuResponse> result = popularityService.getTopPopularMenus();

        // then: TOP 3 가 A, B, C 순 (menuId ASC) — D 는 동률이지만 제외.
        assertThat(result).hasSize(3);
        assertThat(result).extracting(PopularMenuResponse::menuId)
                .containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(result).extracting(PopularMenuResponse::orderCount)
                .containsExactly(2L, 2L, 2L);
    }

    @Test
    @DisplayName("7일 윈도우 밖의 주문은 카운트에서 제외된다")
    void excludesOrdersOlderThanSevenDays() {
        // given: 메뉴 2종.
        Menu a = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        Menu b = menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());

        // A: 윈도우 안(now 기준) 3건 + 윈도우 밖(8일 전) 100건.
        placeOrders(a.getId(), 3);
        placeOrdersAt(a.getId(), 100, LocalDateTime.now().minusDays(8));

        // B: 윈도우 안 2건.
        placeOrders(b.getId(), 2);

        // when
        List<PopularMenuResponse> result = popularityService.getTopPopularMenus();

        // then: 8일 전 100건은 무시. A=3, B=2 만 집계.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).menuId()).isEqualTo(a.getId());
        assertThat(result.get(0).orderCount()).isEqualTo(3L);
        assertThat(result.get(1).menuId()).isEqualTo(b.getId());
        assertThat(result.get(1).orderCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("응답 DTO 의 메뉴 이름/가격이 정확히 매핑된다")
    void responseHasCorrectMenuInfo() {
        // given
        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        placeOrders(menu.getId(), 1);

        // when
        PopularMenuResponse top = popularityService.getTopPopularMenus().get(0);

        // then
        assertThat(top.menuId()).isEqualTo(menu.getId());
        assertThat(top.name()).isEqualTo("아메리카노");
        assertThat(top.price()).isEqualTo(4500L);
        assertThat(top.orderCount()).isEqualTo(1L);
    }

    // ─────────────────────── helpers ───────────────────────

    /** 현재 시각 기준 주문 N건 생성 (윈도우 안). */
    private void placeOrders(Long menuId, int count) {
        for (int i = 0; i < count; i++) {
            orderRepository.save(Order.builder()
                    .userId(1L)
                    .menuId(menuId)
                    .paidAmount(1000L)
                    .build());
        }
    }

    /** 특정 시점에 주문 N건 생성 (윈도우 밖 시뮬레이션). createdAt 을 Reflection 으로 강제 주입. */
    private void placeOrdersAt(Long menuId, int count, LocalDateTime createdAt) {
        for (int i = 0; i < count; i++) {
            Order order = Order.builder()
                    .userId(1L)
                    .menuId(menuId)
                    .paidAmount(1000L)
                    .build();
            setCreatedAt(order, createdAt);
            orderRepository.save(order);
        }
    }

    private static void setCreatedAt(Order order, LocalDateTime when) {
        try {
            Field f = Order.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(order, when);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Reflection 으로 createdAt 설정 실패", e);
        }
    }
}
