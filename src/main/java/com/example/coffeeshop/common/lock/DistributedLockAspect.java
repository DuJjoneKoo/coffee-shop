package com.example.coffeeshop.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link DistributedLock} 처리 Aspect.
 *
 * <p><b>실행 순서가 핵심 — 반드시 트랜잭션보다 먼저 실행되어야 한다.</b>
 * <pre>
 *   [정상 순서]
 *   분산 락 획득 → @Transactional 시작 → 비즈니스 → 트랜잭션 커밋 → 분산 락 해제
 *
 *   [잘못된 순서 — 락이 트랜잭션 안에 있는 경우]
 *   @Transactional 시작 → 락 획득 → 비즈니스 → 락 해제 → 트랜잭션 커밋
 *                                              ↑↑↑ 락이 풀린 시점에 아직 커밋 전!
 *   다른 인스턴스가 즉시 락을 획득해도 직전 트랜잭션의 변경분이 아직 보이지 않음
 *   → Lost Update 재현됨.
 * </pre>
 *
 * <p>이를 위해 {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 로 우선순위를 최상위로 설정하여
 * Spring 의 {@code @Transactional} (기본 우선순위 LOWEST_PRECEDENCE) 보다 바깥에 위치하게 한다.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // @Transactional 보다 먼저 실행되도록
@RequiredArgsConstructor
public class DistributedLockAspect {

    /** 다른 Redis 키와 충돌하지 않도록 일관된 prefix 부여. */
    private static final String LOCK_PREFIX = "LOCK:";

    private final RedissonClient redissonClient;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(com.example.coffeeshop.common.lock.DistributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 어노테이션 메타데이터 추출
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        // 2. SpEL 키 평가 — 메서드 파라미터를 변수로 바인딩하여 동적 키 생성
        String key = LOCK_PREFIX + parseKey(annotation.key(), method, joinPoint.getArgs());
        RLock lock = redissonClient.getLock(key);

        boolean acquired = false;
        try {
            // 3. 락 획득 시도. waitTime 안에 얻으면 true, 못 얻으면 false.
            //    leaseTime 을 명시했으므로 watch dog 비활성 — leaseTime 안에 비즈니스가 끝나야 함.
            acquired = lock.tryLock(annotation.waitTime(), annotation.leaseTime(), annotation.timeUnit());
            if (!acquired) {
                // TODO: BusinessException(LOCK_ACQUISITION_FAILED, 409 CONFLICT) 로 전환 권장.
                //       현재는 Unknown 핸들러로 빠져 500 응답 — 사용자 입장에선 "잠시 후 재시도" 가 맞음.
                throw new IllegalStateException("분산 락 획득 실패: " + key);
            }
            log.debug("분산 락 획득: {}", key);

            // 4. 락을 쥔 채로 원래 메서드 실행 (그 안에서 @Transactional 이 시작됨)
            return joinPoint.proceed();
        } finally {
            // 5. 락 해제 — 반드시 "내가 획득한 락" 이고 "여전히 내 스레드가 보유 중일 때" 만 해제.
            //    leaseTime 만료로 자동 해제된 후 다른 스레드가 잡은 락을 잘못 해제하는 사고 방지.
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("분산 락 해제: {}", key);
            }
        }
    }

    /**
     * SpEL 표현식을 메서드 파라미터 컨텍스트로 평가하여 락 키를 생성한다.
     *
     * <p>예: 표현식 {@code "'point:' + #userId"}, userId=42 → 결과 "point:42"
     */
    private String parseKey(String spel, Method method, Object[] args) {
        // 메서드 파라미터 이름 추출 — 컴파일 시 -parameters 옵션이 있어야 정확히 잡힘.
        // (Spring Boot 의 gradle 플러그인이 기본으로 켜준다.)
        String[] paramNames = nameDiscoverer.getParameterNames(method);
        EvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            // SpEL 변수로 바인딩 → 표현식 내에서 #변수명 으로 접근 가능.
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        Object value = parser.parseExpression(spel).getValue(ctx);
        return String.valueOf(value);
    }
}
