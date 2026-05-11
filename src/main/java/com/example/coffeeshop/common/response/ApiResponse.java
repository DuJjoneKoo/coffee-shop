package com.example.coffeeshop.common.response;

/**
 * 공통 API 응답 포맷.
 *
 * <p>모든 API 응답이 동일 구조 ({@code success / data / message / code}) 를 사용하여
 * 클라이언트에서 일관된 처리가 가능하도록 한다.
 *
 * <p>응답 예시:
 * <pre>
 * 성공:  { "success": true,  "data": {...}, "message": null, "code": null }
 * 실패:  { "success": false, "data": null,  "message": "포인트 잔액이 부족합니다", "code": "INSUFFICIENT_POINT" }
 * </pre>
 *
 * <p>{@code record} 로 정의 — 불변 객체 + equals/hashCode/toString 자동 생성.
 *
 * @param <T> 응답 데이터 타입
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String code
) {

    /** 데이터를 포함한 성공 응답. */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    // [2026-05-11 제거] 기존 no-arg success() 메서드를 제거.
    // 이유: record 컴포넌트 `success` 의 accessor 시그니처(no-arg)와 충돌하여 컴파일 실패.
    //       (record 에서 컴포넌트와 동일한 이름의 no-arg 메서드는 accessor 로 간주되며
    //        반환 타입이 컴포넌트 타입과 일치해야 함 — boolean vs ApiResponse<T> 불일치)
    //       전체 코드베이스에서 호출처 없음(미사용) 이라 안전하게 삭제. 향후 필요 시
    //       `ApiResponse.<Void>success(null)` 또는 메서드명을 `ok()`/`noContent()` 등으로
    //       바꿔서 재도입할 것.

    /**
     * 실패 응답.
     * @param code    도메인 에러 코드 (클라이언트 분기용)
     * @param message 사용자 표시 메시지
     */
    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, message, code);
    }
}
