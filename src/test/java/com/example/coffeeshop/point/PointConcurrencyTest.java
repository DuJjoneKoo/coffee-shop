package com.example.coffeeshop.point;

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
 * 포인트 충전 동시성 테스트.
 *
 * <p>전제: 로컬에 Redis 가 떠 있어야 분산 락이 동작.
 * (Testcontainers 로 격리 환경 구성 권장 - TODO 참고)
 */
@SpringBootTest
class PointConcurrencyTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @BeforeEach
    void setUp() {
        userPointRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 사용자가 동시에 100회 충전해도 잔액 손실이 없어야 한다")
    void concurrentCharge() throws InterruptedException {
        // given
        Long userId = 1L;
        int threadCount = 100;
        long chargeAmount = 1_000L;

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    pointService.charge(userId, chargeAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then
        long balance = pointService.getBalance(userId).balance();
        assertThat(balance).isEqualTo(successCount.get() * chargeAmount);
        // 분산 락이 정상 동작한다면 모두 성공해야 한다
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failureCount.get()).isZero();
    }
}
