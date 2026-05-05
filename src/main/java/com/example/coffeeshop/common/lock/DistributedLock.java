package com.example.coffeeshop.common.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 분산 락을 선언적으로 적용하는 어노테이션.
 *
 * <p>사용 예시:
 * <pre>
 * &#64;DistributedLock(key = "'point:' + #userId")
 * public void chargePoint(Long userId, long amount) { ... }
 * </pre>
 *
 * <p>특징:
 * <ul>
 *   <li>SpEL 로 동적 키 구성 — 메서드 파라미터를 {@code #파라미터명} 형태로 참조.</li>
 *   <li>실제 락 획득/해제는 {@link DistributedLockAspect} 가 처리.</li>
 *   <li>키 prefix({@code "LOCK:"}) 는 Aspect 에서 자동 부여 — 다른 Redis 키와 충돌 방지.</li>
 * </ul>
 *
 * <p>주의: 이 어노테이션은 반드시 public 메서드에 붙여야 함 (Spring AOP 의 프록시 한계).
 * 같은 클래스 내부 호출(self-invocation) 은 프록시를 거치지 않아 락이 동작하지 않음.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 락 키 (SpEL 표현식 지원).
     * 예: {@code "'point:' + #userId"} → 런타임에 "point:42" 와 같이 평가됨.
     * 키는 같은 자원을 다투는 모든 메서드에서 동일해야 함 (충전/결제 모두 "point:userId" 사용).
     */
    String key();

    /**
     * 락 획득 시도 최대 대기 시간.
     * 이 시간 안에 못 얻으면 false 반환 → Aspect 에서 IllegalStateException 발생.
     * 너무 길면 사용자 응답 지연, 너무 짧으면 정상 트래픽도 실패할 수 있음.
     */
    long waitTime() default 5L;

    /**
     * 락 보유 시간 — 이 시간이 지나면 자동 해제.
     *
     * <p>중요: leaseTime 을 명시적으로 지정하면 Redisson 의 watch dog(자동 연장) 이 비활성화됨.
     * 따라서 비즈니스 처리 시간이 leaseTime 을 넘기면 다른 인스턴스가 락을 획득해 동시 진입할 위험이 있음.
     * watch dog 자동 연장이 필요하면 Aspect 의 tryLock 호출에서 leaseTime 인자를 빼야 함.
     */
    long leaseTime() default 3L;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
