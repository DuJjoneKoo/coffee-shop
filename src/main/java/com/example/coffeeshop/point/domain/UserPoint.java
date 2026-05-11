package com.example.coffeeshop.point.domain;

import com.example.coffeeshop.common.exception.BusinessException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 포인트 잔액 엔티티.
 *
 * <p>동시성 전략 검토:
 * <ul>
 *   <li><b>낙관적 락(@Version)</b>: 충돌 빈도가 낮으면 효율적. 충돌 시 OptimisticLockException 재시도 필요.</li>
 *   <li><b>비관적 락(SELECT FOR UPDATE)</b>: DB 레벨 직렬화. 충전/차감이 잦은 사용자는 대기 시간 증가.</li>
 *   <li><b>분산 락(Redisson)</b>: 다중 인스턴스에서 사용자 단위 직렬화. 락 획득 비용은 있지만 DB 부하 감소.</li>
 * </ul>
 *
 * <p>이 프로젝트는 "다수 서버 + 다수 인스턴스" 가 요구사항이므로 기본은 분산 락(Redisson) 으로 가고,
 * 추가 안전장치로 @Version (낙관적 락) 을 두 번째 방어선으로 사용한다.
 * (분산 락이 어떤 이유로 깨져도 DB 가 막아준다.)
 *
 * <p>인덱스 설계:
 * <ul>
 *   <li>{@code idx_point_user} (unique) — userId 로 1건 조회가 거의 모든 요청의 진입점.
 *       unique 제약으로 "한 사용자 = 한 포인트 row" 무결성도 동시에 강제.</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "user_point", indexes = @Index(name = "idx_point_user", columnList = "userId", unique = true))
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙상 기본 생성자 필요. 외부 직접 호출은 막음.
public class UserPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 사용자 식별값. 비즈니스 키. */
    @Column(nullable = false, unique = true)
    private Long userId;

    /** 잔액(원). 음수가 될 수 없음 — {@link #use(long)} 에서 검증. */
    @Column(nullable = false)
    private long balance;

    /**
     * 낙관적 락 버전. UPDATE 시 WHERE version = ? 조건으로 같이 비교되며,
     * 다른 트랜잭션이 먼저 변경했다면 영향받은 행 수가 0 → OptimisticLockException 발생.
     * 분산 락이 깨졌을 때의 마지막 안전장치.
     */
    @Version
    private Long version;

    @Builder
    public UserPoint(Long userId, long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    /**
     * 포인트 충전.
     *
     * <p>도메인 불변식:
     * <ul>
     *   <li>충전 금액은 양수 — 0/음수는 의미 없는 요청이거나 악의적 시도이므로 거부.</li>
     * </ul>
     *
     * @throws BusinessException INVALID_AMOUNT — 0 또는 음수 충전 시
     */
    public void charge(long amount) {
        if (amount <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "충전 금액은 0 보다 커야 합니다", HttpStatus.BAD_REQUEST);
        }
        // 단순 누적. 분산 락 + @Version 으로 동시성은 외부에서 보장됨.
        this.balance += amount;
    }

    /**
     * 포인트 차감 (결제 시 사용).
     *
     * <p>도메인 불변식:
     * <ul>
     *   <li>사용 금액은 양수.</li>
     *   <li>차감 후 잔액이 음수가 되어선 안 됨 → 사전 체크.</li>
     * </ul>
     *
     * @throws BusinessException INVALID_AMOUNT — 0/음수 사용 시
     * @throws BusinessException INSUFFICIENT_POINT — 잔액 부족 시
     */
    public void use(long amount) {
        if (amount <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "사용 금액은 0 보다 커야 합니다", HttpStatus.BAD_REQUEST);
        }
        if (this.balance < amount) {
            // 잔액 부족은 "주문 직전 결제 단계" 에서 가장 빈번한 도메인 예외.
            // 별도 코드로 분리하여 클라이언트가 충전 유도 UX 로 분기할 수 있게 함.
            throw new BusinessException("INSUFFICIENT_POINT", "포인트 잔액이 부족합니다", HttpStatus.BAD_REQUEST);
        }
        this.balance -= amount;
    }
}
