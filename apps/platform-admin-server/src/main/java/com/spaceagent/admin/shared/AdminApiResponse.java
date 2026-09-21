package com.spaceagent.admin.shared;

public record AdminApiResponse<T>(boolean success, String code, String message, T data) {
    public static <T> AdminApiResponse<T> ok(T data) {
        return new AdminApiResponse<>(true, "OK", "success", data);
    }

    public static AdminApiResponse<Void> error(String code, String message) {
        return new AdminApiResponse<>(false, code, message, null);
    }
}
