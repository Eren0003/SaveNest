package com.savenest.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Consistent error envelope: machine-readable code, safe message, request ID. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ErrorBody error) {

    public record FieldError(String field, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, String requestId, List<FieldError> fields, Object details) {
    }

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(new ErrorBody(code, message, requestId, null, null));
    }

    public static ApiError of(String code, String message, String requestId, List<FieldError> fields, Object details) {
        return new ApiError(new ErrorBody(code, message, requestId, fields, details));
    }
}
