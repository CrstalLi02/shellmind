package com.shellmind.domain.llm.service;

public class ModelConfigException extends RuntimeException {

    public ModelConfigException(String message) {
        super(message);
    }

    public ModelConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
