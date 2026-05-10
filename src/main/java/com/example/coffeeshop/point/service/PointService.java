package com.example.coffeeshop.point.service;

import com.example.coffeeshop.common.exception.BusinessException;
import com.example.coffeeshop.common.lock.DistributedLock;
import com.example.coffeeshop.point.domain.UserPoint;
import com.example.coffeeshop.point.dto.PointResponse;
import com.example.coffeeshop.point.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 충전 / 차감 서비스.
 *
 * <p>핵심 동시성 시나리오:
 * <pre>
 * [같은 사용자가 동시에 1000원 충전을 두 번 요청]
 *   T1: read balance=0  ->  + 1000  ->  write balance=1000
 *   T2: read balance=0  ->  + 1000  ->  write balance=1000  (T1 의 변경분 유실 — Lost Update)
 * </pre>
 *
 * <p>해결 전략 (이중 방어):
 * <ol>
 *   <li><b>1차: 분산 락 (Redisson)</b> — 사용자 ID 단위로 직렬화. 다중 인스턴스 환경에서도
 *       동일 키에 대해 한 번에 한 스레드만 임계 구역 진입 가능.</li>
 *   <li><b>2차: @Version 낙관적 락</b> — 분산 락이 어떤 이유로(네트워크 단절, leaseTime 만료 등)
 *       풀려서 두 트랜잭션이 동시에 진입하더라도 DB 레벨에서 마지막 방어선 역할.</li>
 * </ol>
 *
 * <p>왜 비관적 락(SELECT FOR UPDATE) 을 쓰지 않았나?
 * <ul>
 *   <li>다중 인스턴스에서 같은 사용자에 트래픽이 몰리면 DB 커넥션이 행(row) 락 대기로 묶여
 *       커넥션 풀 고갈로 이어질 위험.</li>
 *   <li>Redis 분산 락으로 직렬화 책임을 분리하여 DB 부하를 줄이고 인스턴스 확장 시 유리.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PointService {

    private final UserPointRepository userPointRepository;

    /**
     * 포인트 충전.
     *
     * <p>락 키 규칙: {@code "point:" + userId}
     * — 충전과 결제가 동일 키를 공유해야 "충전 중인 잔액에 대해 결제가 끼어드는" 케이스 방지.
     *
     * <p>어노테이션 실행 순서 (DistributedLockAspect 가 HIGHEST_PRECEDENCE):
     * <pre>
     *   분산 락 획득 → @Transactional 시작 → 비즈니스 로직 → 트랜잭션 커밋 → 분산 락 해제
     * </pre>
     * 이 순서가 보장되어야 "커밋 직전에 락이 풀려 다른 인스턴스가 stale 데이터를 보는" 사고를 막을 수 있다.
     *
     * @param userId 사용자 식별값
     * @param amount 충전 금액 (1원 = 1P, 음수/0 은 도메인에서 거부)
     * @return 충전 후 잔액 응답
     */
    @DistributedLock(key = "'point:' + #userId")
    @Transactional
    public PointResponse charge(Long userId, long amount) {
        // 사용자 포인트 row 가 없으면 잔액 0 으로 신규 생성 (최초 충전 케이스).
        // orElseGet 의 람다는 Optional 이 비어있을 때만 호출되어 불필요한 INSERT 방지.
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseGet(() -> userPointRepository.save(
                        UserPoint.builder().userId(userId).balance(0L).build()));

        // 도메인 메서드에서 금액 검증 + 잔액 증가 (불변식은 도메인이 책임).
        userPoint.charge(amount);

        // JPA dirty checking 으로 트랜잭션 커밋 시점에 UPDATE 자동 발행.
        // 이때 @Version 이 함께 비교되어 다른 트랜잭션이 먼저 변경했다면 OptimisticLockException.
        return PointResponse.from(userPoint);
    }

    /**
     * 결제 시 호출되는 포인트 차감.
     *
     * <p>주의: 락 키를 {@code "point:" + userId} 로 통일하여 충전과 동일 자원으로 인식되게 함.
     * 만약 차감만 별도 키를 쓰면 "충전 중 차감"이 동시에 일어나 잔액 계산이 어긋날 수 있다.
     *
     * <p>호출 시점: {@code OrderService.order()} 의 트랜잭션 안에서 호출됨.
     * propagation 은 default(REQUIRED) 라 기존 트랜잭션에 합류 → 주문 저장 실패 시 차감도 함께 롤백.
     *
     * <p><b>[2026-05-11 수정 — 리뷰 블로커 #1]</b>
     * 본 메서드의 {@code @DistributedLock} 을 제거.
     * 이유: 락이 트랜잭션 안쪽(usePoint 단위) 에 있으면 "락 해제 → OrderService 트랜잭션 커밋"
     * 사이 갭에서 다른 인스턴스가 stale 잔액을 읽고 진입할 수 있음(Lost Update 시나리오).
     * 분산 락은 호출자인 {@code OrderService.order()} 에 부착되어 트랜잭션 전체를 감싸며,
     * 키({@code "point:" + userId}) 는 동일하므로 충전/결제 사이 직렬화는 그대로 유지됨.
     * 단독 결제 호출(있을 경우) 대비 안전성은 도메인 {@code @Version} 이 마지막 방어선.
     *
     * @throws BusinessException 사용자 포인트 정보가 없거나(POINT_NOT_FOUND) 잔액 부족 시(INSUFFICIENT_POINT)
     */
    @Transactional
    public void usePoint(Long userId, long amount) {
        // 결제는 "충전 이력이 있는 사용자" 만 가능. 없는 경우 즉시 예외.
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        "POINT_NOT_FOUND", "사용자 포인트 정보가 없습니다", HttpStatus.NOT_FOUND));

        // 잔액 검증 + 차감은 도메인 메서드에 캡슐화.
        // 차감 실패(잔액 부족) 시 BusinessException 으로 빠지면서 트랜잭션 자동 롤백.
        userPoint.use(amount);
    }

    /**
     * 잔액 단순 조회.
     *
     * <p>읽기 전용이므로 {@code readOnly = true} 로 dirty checking 비용 절감 + 슬레이브 라우팅 가능.
     */
    @Transactional(readOnly = true)
    public PointResponse getBalance(Long userId) {
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        "POINT_NOT_FOUND", "사용자 포인트 정보가 없습니다", HttpStatus.NOT_FOUND));
        return PointResponse.from(userPoint);
    }
}
