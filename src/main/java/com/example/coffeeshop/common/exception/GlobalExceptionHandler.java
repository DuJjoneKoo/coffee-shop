package com.example.coffeeshop.common.exception;

import com.example.coffeeshop.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 핸들러 — 모든 컨트롤러에서 발생하는 예외를 일관된 {@link ApiResponse} 포맷으로 변환.
 *
 * <p>처리 우선순위 (구체적인 타입이 우선 매칭):
 * <ol>
 *   <li>{@link BusinessException} — 도메인이 의도적으로 던진 예외 → 4xx 위주.</li>
 *   <li>{@link MethodArgumentNotValidException} — {@code @Valid} 검증 실패 → 400.</li>
 *   <li>{@link ObjectOptimisticLockingFailureException} — JPA {@code @Version} 충돌 → 409 (블로커 #3).</li>
 *   <li>{@link DataIntegrityViolationException} — DB 무결성 위반 (unique 등) → 409 (블로커 #3).</li>
 *   <li>{@link Exception} — 그 외 모든 예외 → 500.</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 도메인 예외 핸들링 — code/status 를 그대로 응답에 매핑.
     * WARN 레벨 로그: 운영자 입장에서 "사용자 잘못/예측 가능 케이스" 라 알람까진 불필요.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("비즈니스 예외: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * Bean Validation 실패 처리 — 첫 번째 필드 에러만 메시지로 노출.
     * 여러 필드가 한꺼번에 틀려도 가장 먼저 발견된 것만 응답하여 단순화.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("요청 값이 올바르지 않습니다");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    /**
     * [2026-05-11 추가 — 리뷰 블로커 #3]
     * JPA 낙관적 락(@Version) 충돌 처리.
     *
     * <p>발생 경로: 분산 락이 일시적으로 깨진 상황(네트워크 단절, leaseTime 만료 등) 에서
     * 두 트랜잭션이 동시에 같은 row 를 변경 → 늦게 커밋하는 쪽이 영향행 0 → 본 예외.
     *
     * <p>매핑 의도: 409 CONFLICT. "리소스의 현재 상태가 요청 시점과 달라졌다" 의 정확한 의미.
     * 이전에는 미분류 핸들러로 빠져 500 으로 노출되어 정상 동작 코드가 간헐 실패하는 UX 였음.
     * WARN 레벨: 자주 발생하면 분산 락 헬스 점검 필요한 신호.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONCURRENT_MODIFICATION", "동시 변경이 감지되었습니다. 다시 시도해 주세요"));
    }

    /**
     * [2026-05-11 추가 — 리뷰 블로커 #3]
     * DB 무결성 제약 위반 처리 (unique 제약, NOT NULL, FK 등).
     *
     * <p>주요 발생 경로: 같은 사용자의 최초 포인트 row INSERT 가 동시에 발생해 unique 충돌.
     * 분산 락이 막아주지만 락이 깨진 케이스 대비 마지막 방어선.
     *
     * <p>매핑 의도: 409 CONFLICT. 메시지에 사유는 노출하지 않음 (정보 유출 방지).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DB 무결성 위반: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA_INTEGRITY_VIOLATION", "요청을 처리할 수 없는 상태입니다. 다시 시도해 주세요"));
    }

    /**
     * 미분류 예외 — 서버 측 결함 가능성. ERROR 로그 + 500 응답.
     * 클라이언트에는 내부 메시지를 노출하지 않음 (정보 유출 방지).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("예기치 않은 예외", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다"));
    }
}
