package com.gxtodo.server;

final class ApiException extends RuntimeException {
    final String status;

    ApiException(int status, String message) {
        super(message);
        this.status = Integer.toString(status);
    }
}
