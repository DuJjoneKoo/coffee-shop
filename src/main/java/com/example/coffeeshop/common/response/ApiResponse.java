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

    /** 데이터 없는 성공 응답 (예: 충전 완료, 삭제 성공 등). */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(true, null, null, null);
    }

    /**
     * 실패 응답.
     * @param code    도메인 에러 코드 (클라이언트 분기용)
     * @param message 사용자 표시 메시지
     */
    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, message, code);
    }
}
