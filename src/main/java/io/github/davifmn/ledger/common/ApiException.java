package io.github.davifmn.ledger.common;

import org.springframework.http.HttpStatus;

/** An error that maps to an HTTP status and an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, null);
    }

    public ApiException(HttpStatus status, String detail, Long retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
    }

    public static ApiException tooManyRequests(String detail, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail, retryAfterSeconds);
    }
}
