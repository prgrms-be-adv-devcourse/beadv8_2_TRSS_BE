package com.backend.global.rsData;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record RsData<T>(String resultCode, String msg, T data) {

    public static <T> RsData<T> of(String resultCode, String msg, T data) {
        return new RsData<>(resultCode, msg, data);
    }

    public static <T> RsData<T> of(String resultCode, String msg) {
        return new RsData<>(resultCode, msg, null);
    }

    @JsonIgnore
    public int statusCode() {
        return Integer.parseInt(resultCode.split("-", 2)[0]);
    }
}
