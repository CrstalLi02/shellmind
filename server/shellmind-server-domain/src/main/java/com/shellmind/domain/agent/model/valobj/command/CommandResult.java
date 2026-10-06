package com.shellmind.domain.agent.model.valobj.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command execution result (Client → Server, posted back over HTTP).
 *
 * @author shellmind dev
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandResult {

    /** Corresponding command ID */
    private String cmdId;

    /** Session ID */
    private String sessionId;

    /** Execution status */
    private Status status;

    /** Command output */
    private String output;

    /** Exit code */
    private int exitCode;

    /** Duration (milliseconds) */
    private long durationMs;

    /** Error message (only when status=error) */
    private String error;

    /** Whether the command succeeded */
    private boolean success;

    public enum Status {
        SUCCESS, ERROR, TIMEOUT, CANCELLED, DISCONNECTED
    }

    /**
     * Build a success result.
     */
    public static CommandResult success(String cmdId, String sessionId, String output, int exitCode, long durationMs) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .sessionId(sessionId)
                .status(Status.SUCCESS)
                .output(output)
                .exitCode(exitCode)
                .durationMs(durationMs)
                .success(exitCode == 0)
                .build();
    }

    /**
     * Build an error result.
     */
    public static CommandResult error(String cmdId, String sessionId, String error, long durationMs) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .sessionId(sessionId)
                .status(Status.ERROR)
                .error(error)
                .durationMs(durationMs)
                .success(false)
                .build();
    }

    /**
     * Build a timeout result.
     */
    public static CommandResult timeout(String cmdId) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .status(Status.TIMEOUT)
                .error("Command execution timed out")
                .success(false)
                .build();
    }

    /**
     * Build a disconnected result.
     */
    public static CommandResult disconnected(String cmdId) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .status(Status.DISCONNECTED)
                .error("Client connection disconnected")
                .success(false)
                .build();
    }
}
