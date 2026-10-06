package com.shellmind.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local command result posted back by the client (also used for replay).
 * <p>
 * exitCode / durationMs / success are primitives: missing fields are treated as a bad request (400).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandResultDTO {

    public enum Status {
        SUCCESS, ERROR, TIMEOUT, CANCELLED, DISCONNECTED
    }

    private String cmdId;
    private String sessionId;
    private Status status;
    private String output;
    private int exitCode;
    private long durationMs;
    private String error;
    private boolean success;
}
