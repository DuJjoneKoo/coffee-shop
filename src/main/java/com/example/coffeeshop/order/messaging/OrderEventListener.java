package com.example.coffeeshop.order.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 생성 이벤트를 트랜잭션 커밋 이후에 발행.
 *
 * <p>왜 AFTER_COMMIT 인가?
 * <ul>
 *   <li>주문/포인트 트랜잭션이 롤백되었는데 외부 시스템에는 이미 데이터가 나가면 정합성 붕괴.</li>
 *   <li>커밋 후 발행하면 적어도 "DB 에 존재하는 주문만" 외부로 나간다는 보장.</li>
 * </ul>
 *
 * <p>대안 비교:
 * <table>
 *   <tr><th>페이즈</th><th>특징</th><th>리스크</th></tr>
 *   <tr><td>BEFORE_COMMIT</td><td>커밋 직전</td><td>발행 후 커밋 실패 시 외부 데이터 ↔ DB 불일치</td></tr>
 *   <tr><td>AFTER_COMMIT (현재)</td><td>커밋 직후</td><td>발행 직전 서버 다운 시 이벤트 유실</td></tr>
 *   <tr><td>AFTER_ROLLBACK</td><td>롤백 후</td><td>실패 알림용 (현재 미사용)</td></tr>
 * </table>
 *
 * <p>한계 (README 트러블슈팅 후보):
 * <ul>
 *   <li>커밋 직후 ~ 발행 직전 서버 다운 시 이벤트 유실.</li>
 *   <li>외부 시스템 장애 시 재시도 정책 필요.</li>
 *   <li>완전한 정확성을 위해선 Outbox 패턴(같은 트랜잭션에 outbox 테이블 INSERT → 별도 워커 polling)
 *       또는 CDC(Debezium 등) 가 정답.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final DataPlatformPublisher dataPlatformPublisher;

    /**
     * 주문 트랜잭션 커밋 이후 호출됨.
     *
     * <p>예외 처리 정책: <b>swallow + 로깅</b>.
     * 외부 시스템 장애가 메인(주문 생성) 흐름에 영향을 주지 않도록 격리한다.
     * 단, 이렇게 swallow 한 이벤트는 사실상 유실되므로 운영 환경에서는
     * DLQ(Dead Letter Queue) 또는 알람 연동이 필요.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            dataPlatformPublisher.publish(event);
        } catch (Exception e) {
            // 외부 시스템 장애가 메인 흐름에 영향을 주지 않도록 swallow + 로깅.
            // TODO: DLQ / 재시도 큐 / 알람 연동.
            log.error("데이터 플랫폼 발행 실패. event={}", event, e);
        }
    }
}
