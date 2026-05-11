package com.example.coffeeshop.point;

import com.example.coffeeshop.common.exception.BusinessException;
import com.example.coffeeshop.point.domain.UserPoint;
import com.example.coffeeshop.point.dto.PointResponse;
import com.example.coffeeshop.point.repository.UserPointRepository;
import com.example.coffeeshop.point.service.PointService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PointService 단위 테스트 — 도메인 로직만 검증 (분산 락/트랜잭션은 @SpringBootTest 통합테스트에서).
 *
 * <p>커버 범위:
 * <ul>
 *   <li>charge: 최초 충전(row 신규 생성) / 누적 / 음수·0 거부</li>
 *   <li>usePoint: 정상 차감 / 미존재 사용자 / 잔액 부족</li>
 *   <li>getBalance: 정상 / 미존재</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @Mock
    private UserPointRepository userPointRepository;

    @InjectMocks
    private PointService pointService;

    // ─────────────────── charge ───────────────────

    @Test
    @DisplayName("처음 충전하는 사용자는 잔액 0 으로 시작한 row 가 생성된 뒤 누적된다")
    void charge_createsNewRowOnFirstCharge() {
        // given
        Long userId = 1L;
        UserPoint newRow = UserPoint.builder().userId(userId).balance(0L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.empty());
        given(userPointRepository.save(any(UserPoint.class))).willReturn(newRow);

        // when
        PointResponse result = pointService.charge(userId, 1000L);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.balance()).isEqualTo(1000L);
        verify(userPointRepository, times(1)).save(any(UserPoint.class));
    }

    @Test
    @DisplayName("기존 사용자는 신규 INSERT 없이 잔액에 누적된다")
    void charge_accumulatesOnExistingUser() {
        // given
        Long userId = 1L;
        UserPoint existing = UserPoint.builder().userId(userId).balance(500L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.of(existing));

        // when
        PointResponse result = pointService.charge(userId, 1500L);

        // then
        assertThat(result.balance()).isEqualTo(2000L);
        // dirty checking 의존 → save 호출 없음.
        verify(userPointRepository, never()).save(any());
    }

    @Test
    @DisplayName("충전 금액이 0 이하면 INVALID_AMOUNT 로 거부된다")
    void charge_rejectsZeroOrNegative() {
        // given
        Long userId = 1L;
        UserPoint existing = UserPoint.builder().userId(userId).balance(0L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.of(existing));

        // when / then
        assertThatThrownBy(() -> pointService.charge(userId, 0L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AMOUNT");
        assertThatThrownBy(() -> pointService.charge(userId, -100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AMOUNT");
    }

    // ─────────────────── usePoint ───────────────────

    @Test
    @DisplayName("정상 차감 시 잔액이 줄어든다")
    void usePoint_subtractsBalance() {
        // given
        Long userId = 1L;
        UserPoint existing = UserPoint.builder().userId(userId).balance(5000L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.of(existing));

        // when
        pointService.usePoint(userId, 3000L);

        // then
        assertThat(existing.getBalance()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("충전 이력이 없는 사용자가 차감 시도하면 POINT_NOT_FOUND")
    void usePoint_throwsWhenUserMissing() {
        // given
        Long userId = 999L;
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> pointService.usePoint(userId, 1000L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "POINT_NOT_FOUND");
    }

    @Test
    @DisplayName("잔액 부족이면 INSUFFICIENT_POINT 로 거부되고 잔액은 그대로 유지")
    void usePoint_throwsOnInsufficientBalance() {
        // given
        Long userId = 1L;
        UserPoint existing = UserPoint.builder().userId(userId).balance(500L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.of(existing));

        // when / then
        assertThatThrownBy(() -> pointService.usePoint(userId, 1000L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_POINT");

        assertThat(existing.getBalance()).isEqualTo(500L); // 불변
    }

    // ─────────────────── getBalance ───────────────────

    @Test
    @DisplayName("getBalance: 정상 조회")
    void getBalance_returnsCurrentBalance() {
        // given
        Long userId = 1L;
        UserPoint existing = UserPoint.builder().userId(userId).balance(7000L).build();
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.of(existing));

        // when
        PointResponse result = pointService.getBalance(userId);

        // then
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.balance()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("getBalance: 미존재 사용자는 POINT_NOT_FOUND")
    void getBalance_throwsWhenUserMissing() {
        // given
        Long userId = 999L;
        given(userPointRepository.findByUserId(userId)).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> pointService.getBalance(userId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "POINT_NOT_FOUND");
    }
}
