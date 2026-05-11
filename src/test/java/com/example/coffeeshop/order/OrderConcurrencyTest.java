package com.example.coffeeshop.order;

import com.example.coffeeshop.common.exception.BusinessException;
import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.order.repository.OrderRepository;
import com.example.coffeeshop.order.service.OrderService;
import com.example.coffeeshop.point.repository.UserPointRepository;
import com.example.coffeeshop.point.service.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 사용자가 동시에 결제(주문) 를 시도할 때 잔액/주문 수 정합성 검증.
 *
 * <p><b>핵심 검증 시나리오:</b>
 * 잔액이 한도(allowedSuccess 건) 까지만 결제 허용되어야 하고,
 * 나머지는 잔액 부족(INSUFFICIENT_POINT) 또는 락 경합(LOCK_ACQUISITION_FAILED) 으로 실패해야 한다.
 * 어떤 경우에도:
 * <ul>
 *   <li>최종 잔액 = initialBalance − (성공 건수 × 메뉴 가격)</li>
 *   <li>주문 row 수 = 성공 건수</li>
 *   <li>"성공이 한도 초과" 또는 "잔액이 음수" 같은 정합성 위반은 절대 없음</li>
 * </ul>
 *
 * <p>전제: 로컬에 MySQL(3308) + Redis(6380) 가 떠 있어야 분산 락이 동작 (docker compose up -d).
 * Testcontainers 격리는 별도 개선 항목 (PointConcurrencyTest 와 동일한 TODO).
 *
 * <p>왜 PointConcurrencyTest 와 별도 테스트인가?
 * <ul>
 *   <li>1순위 리뷰에서 분산 락이 {@code OrderService.order()} 레벨로 끌어올려졌으므로,
 *       락 경계가 트랜잭션을 감싸는지를 *결제 흐름 전체* 로 검증해야 의미가 있음.</li>
 *   <li>주문 저장(INSERT) + 포인트 차감(UPDATE) + 이벤트 발행이 한 트랜잭션 안에서 일어나는데,
 *       경합 시 "주문은 만들어졌는데 차감이 안 됨" / "차감은 됐는데 주문 row 없음" 같은
 *       불일치가 없는지가 핵심.</li>
 * </ul>
 */
@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private MenuRepository menuRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserPointRepository userPointRepository;

    @BeforeEach
    void setUp() {
        // 순서 중요: orders → user_point → menu. FK 가 없어도 명시적으로 의존 그래프 따라 정리.
        orderRepository.deleteAll();
        userPointRepository.deleteAll();
        menuRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 사용자가 동시에 50회 결제 시도 시 잔액 한도(30회) 까지만 성공한다")
    void concurrentOrder() throws InterruptedException {
        // given — 50회 시도 중 정확히 30회만 성공 가능한 조건.
        Long userId = 1L;
        long menuPrice = 1_000L;
        long initialBalance = 30_000L;          // 정확히 30회 결제 가능
        int allowedSuccess = (int) (initialBalance / menuPrice);
        int threadCount = 50;

        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(menuPrice).build());
        pointService.charge(userId, initialBalance);

        // when — 50 스레드가 동시에 주문 시도.
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicInteger lockFailedCount = new AtomicInteger();
        AtomicInteger otherFailCount = new AtomicInteger();

        Long menuId = menu.getId();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    orderService.order(userId, menuId);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    // 잔액 부족과 락 경합은 모두 "예상된" 실패 — 분리하여 카운트.
                    switch (e.getCode()) {
                        case "INSUFFICIENT_POINT" -> insufficientCount.incrementAndGet();
                        case "LOCK_ACQUISITION_FAILED" -> lockFailedCount.incrementAndGet();
                        default -> otherFailCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then — 정합성 불변식들:
        long finalBalance = pointService.getBalance(userId).balance();
        long orderRowCount = orderRepository.count();

        // 1. 의도되지 않은 실패는 0건이어야 함 (NPE, OptimisticLock 등이 새어나오면 위험).
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외 발생 — 코드 결함 가능성")
                .isZero();

        // 2. 시도 횟수 = 결과 합계.
        assertThat(successCount.get() + insufficientCount.get() + lockFailedCount.get())
                .as("모든 시도가 분류되어야 함")
                .isEqualTo(threadCount);

        // 3. 성공은 잔액 한도 이하 — 가장 핵심. 한도 초과 = race condition = Lost Update 발생.
        assertThat(successCount.get())
                .as("성공이 잔액 한도(%d) 를 초과하면 안 됨 — race condition 발생", allowedSuccess)
                .isLessThanOrEqualTo(allowedSuccess);

        // 4. 잔액 정합성: 최종 잔액 = 초기 잔액 - 성공 × 가격. 음수 절대 불가.
        assertThat(finalBalance)
                .as("최종 잔액이 초기잔액 − 성공×가격 과 일치해야 함")
                .isEqualTo(initialBalance - successCount.get() * menuPrice);
        assertThat(finalBalance)
                .as("잔액이 음수가 되면 안 됨")
                .isGreaterThanOrEqualTo(0L);

        // 5. 주문 row 수 = 성공 건수. 트랜잭션 원자성 검증.
        assertThat(orderRowCount)
                .as("주문 row 수가 성공 건수와 일치해야 함 (트랜잭션 원자성)")
                .isEqualTo(successCount.get());

        // 6. 락 경합이 적게 발생할 때(이상적인 경우), 성공은 정확히 한도와 일치 — 강한 보장.
        //    락 실패가 0건이면 모든 시도가 직렬화되어 한도까지 다 채워졌어야 함.
        if (lockFailedCount.get() == 0) {
            assertThat(successCount.get())
                    .as("락 경합 없이 모든 요청이 직렬화됐다면 성공은 정확히 한도와 같아야 함")
                    .isEqualTo(allowedSuccess);
            assertThat(insufficientCount.get())
                    .as("락 경합 없을 때 실패는 모두 잔액 부족이어야 함")
                    .isEqualTo(threadCount - allowedSuccess);
            assertThat(finalBalance).isZero();
        }
    }
}
