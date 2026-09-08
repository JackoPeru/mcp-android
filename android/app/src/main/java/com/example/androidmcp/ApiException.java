package com.example.androidmcp;

/** Stable error returned at the HTTP RPC boundary. */
public final class ApiException extends Exception {
    public final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }
}
