package com.example.coffeeshop.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 비즈니스 예외 베이스 클래스.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>도메인에서 발생하는 "예측 가능한" 예외는 모두 이 클래스(또는 상속) 로 표현.</li>
 *   <li>{@link RuntimeException} 상속 — Spring 의 기본 트랜잭션 롤백 규칙(언체크 예외) 에 자동 부합.
 *       즉 이 예외가 던져지면 진행 중인 트랜잭션이 자동으로 롤백된다.</li>
 *   <li>{@code code} (도메인 코드) + {@code status} (HTTP 상태) 를 함께 보관 →
 *       {@link GlobalExceptionHandler} 에서 일관된 응답 포맷으로 변환.</li>
 * </ul>
 *
 * <p>사용 예시:
 * <pre>
 * throw new BusinessException(
 *     "INSUFFICIENT_POINT", "포인트 잔액이 부족합니다", HttpStatus.BAD_REQUEST);
 * </pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 도메인 에러 코드 — 클라이언트가 분기 처리할 수 있는 식별자. */
    private final String code;

    /** HTTP 응답 상태 — 4xx/5xx 분류. */
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
