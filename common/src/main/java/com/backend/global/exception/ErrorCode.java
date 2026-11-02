package com.backend.global.exception;

public interface ErrorCode {
    int status();
    String name();
    String msg();

    default String resultCode() {
        return status() + "-" + name();
    }
}
