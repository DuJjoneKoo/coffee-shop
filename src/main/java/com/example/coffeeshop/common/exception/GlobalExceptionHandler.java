package com.example.coffeeshop.common.exception;

import com.example.coffeeshop.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
