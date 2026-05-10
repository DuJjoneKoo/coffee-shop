package com.example.coffeeshop.order.service;

import com.example.coffeeshop.common.exception.BusinessException;
import com.example.coffeeshop.common.lock.DistributedLock;
import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.order.domain.Order;
import com.example.coffeeshop.order.dto.OrderResponse;
import com.example.coffeeshop.order.messaging.OrderCreatedEvent;
import com.example.coffeeshop.order.repository.OrderRepository;
import com.example.coffeeshop.point.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문/결제 서비스.
 *
 * <p>흐름:
 * <ol>
 *   <li>메뉴 조회 (가격 확정)</li>
 *   <li>포인트 차감 ({@link PointService#usePoint} 가 분산 락 + @Version)</li>
 *   <li>주문 저장</li>
 *   <li>{@link OrderCreatedEvent} 발행 → AFTER_COMMIT 에서 데이터 플랫폼 전송</li>
 * </ol>
 *
 * <p>트랜잭션 경계:
 * <ul>
 *   <li>{@code order()} 가 트랜잭션 시작점. {@code pointService.usePoint()} 는 propagation REQUIRED 로 합류.</li>
 *   <li>주문 저장 실패 → 포인트 차감도 함께 롤백 (원자성 보장).</li>
 *   <li>이벤트 발행은 {@code publishEvent} 만 호출하고 실제 외부 전송은
 *       트랜잭션 커밋 이후로 미룬다. 발행 시점에 트랜잭션이 아직 살아있으면 의미 없음.</li>
 * </ul>
 *
 * <p><b>알려진 한계 (면접 예상 질문):</b>
 * 분산 락이 {@code PointService} 메서드 단위에 걸려 있어, "락 해제 ↔ OrderService 트랜잭션 커밋"
 * 사이에 미세한 시간 갭이 존재. 엄밀한 동시성 안전을 원하면 락을 OrderService 레벨로 끌어올려
 * "락이 트랜잭션을 감싸는" 구조로 재구성해야 함. README 트러블슈팅에 trade-off 기록 권장.
 *
 * <p><b>[2026-05-11 수정 — 리뷰 블로커 #1 해소]</b>
 * 위 "알려진 한계" 를 실제로 해결. {@code PointService.usePoint} 의 {@code @DistributedLock} 을 제거하고,
 * 본 {@code order()} 메서드에 락을 부착하여 락이 OrderService 트랜잭션 전체를 감싸도록 변경.
 * <pre>
 *   [수정 후 실행 순서]
 *   DistributedLockAspect 락 획득 (HIGHEST_PRECEDENCE)
 *     → @Transactional 시작
 *     → 메뉴 조회 / 포인트 차감(REQUIRED 합류) / 주문 저장 / 이벤트 발행 등록
 *     → @Transactional 커밋
 *     → AFTER_COMMIT 리스너 (외부 발행)
 *     → DistributedLockAspect 락 해제
 * </pre>
 * 락 해제 시점에 이미 커밋이 끝나 있으므로 "락 해제 ↔ 커밋" 갭이 사라짐.
 * leaseTime 은 외부 발행/메뉴 조회 등이 포함되어 충전 단위(3초) 보다 길게 잡음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final MenuRepository menuRepository;
    private final OrderRepository orderRepository;
    private final PointService pointService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 주문 + 결제.
     *
     * @param userId 사용자 식별값
     * @param menuId 주문할 메뉴 ID
     * @return 생성된 주문 응답
     * @throws BusinessException MENU_NOT_FOUND — 존재하지 않는 메뉴
     * @throws BusinessException INSUFFICIENT_POINT — 포인트 잔액 부족
     * @throws BusinessException POINT_NOT_FOUND — 충전 이력이 없는 사용자
     * @throws BusinessException LOCK_ACQUISITION_FAILED — 같은 사용자의 동시 요청 혼잡 시 (429)
     */
    // [2026-05-11 수정 — 리뷰 블로커 #1]
    // 분산 락을 OrderService 레벨로 끌어올려 트랜잭션 전체를 감싸도록 변경.
    // 키는 충전/차감과 동일하게 'point:userId' — 같은 자원을 다투는 모든 메서드가 같은 키를 공유해야 함.
    // leaseTime 을 10초로 늘린 이유: 메뉴 조회 + 포인트 차감 + 주문 저장 + AFTER_COMMIT 외부 발행까지
    // 모두 락 안에서 끝나야 하므로 단순 충전(3초) 대비 충분한 여유 확보.
    @DistributedLock(key = "'point:' + #userId", leaseTime = 10L)
    @Transactional
    public OrderResponse order(Long userId, Long menuId) {
        // 1. 메뉴 조회 — 가격을 DB 의 권위 있는 값으로 확정.
        //    클라이언트가 보낸 가격을 신뢰하지 않음으로써 가격 위변조 공격 차단.
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(
                        "MENU_NOT_FOUND", "존재하지 않는 메뉴입니다", HttpStatus.NOT_FOUND));

        long price = menu.getPrice();

        // 2. 포인트 차감 — 잔액 검증 + @Version 보호.
        //    [2026-05-11 수정] 분산 락은 OrderService.order() 메서드 어노테이션으로 이동.
        //                    PointService.usePoint() 자체에는 더 이상 락이 없고, REQUIRED 로 본 트랜잭션에 합류만 함.
        //    여기서 예외가 던져지면 (잔액 부족 등) 트랜잭션이 롤백되어 주문 자체가 만들어지지 않음.
        pointService.usePoint(userId, price);

        // 3. 주문 저장 — 결제 성공 후에만 도달. paidAmount 는 차감한 가격 그대로.
        Order order = orderRepository.save(Order.builder()
                .userId(userId)
                .menuId(menuId)
                .paidAmount(price)
                .build());

        // 4. 이벤트 발행 — 외부 데이터 플랫폼 전송용.
        //    publishEvent 호출 자체는 즉시 끝나지만, OrderEventListener 가 @TransactionalEventListener(AFTER_COMMIT)
        //    이라 실제 외부 전송은 이 메서드의 트랜잭션 커밋이 성공한 직후에 발생.
        //    → "롤백된 주문이 외부 시스템에는 발행되는" 정합성 사고 방지.
        eventPublisher.publishEvent(new OrderCreatedEvent(
                order.getId(), userId, menuId, price, order.getCreatedAt()));

        return OrderResponse.from(order);
    }
}
