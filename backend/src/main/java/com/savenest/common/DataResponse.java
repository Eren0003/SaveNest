package com.savenest.common;

/** Success envelope: {@code {"data": ...}}. */
public record DataResponse<T>(T data) {
    public static <T> DataResponse<T> of(T data) {
        return new DataResponse<>(data);
    }
}
