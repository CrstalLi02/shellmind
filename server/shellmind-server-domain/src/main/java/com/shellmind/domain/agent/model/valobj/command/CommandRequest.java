package com.shellmind.domain.agent.model.valobj.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command request (Server → Client, dispatched over SSE).
 *
 * @author shellmind dev
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandRequest {

    /** Unique command ID */
    private String cmdId;

    /** Command type */
    private String type;

    /** Command content */
    private String command;

    /** Working directory */
    private String cwd;

    /** Timeout (milliseconds) */
    private long timeoutMs;

    /** Associated session ID */
    private String sessionId;

    /**
     * Build a local-execution command.
     */
    public static CommandRequest executeLocal(String sessionId, String command, String cwd, long timeoutMs) {
        return CommandRequest.builder()
                .cmdId("cmd_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId())
                .type("execute_local_command")
                .command(command)
                .cwd(cwd)
                .timeoutMs(timeoutMs)
                .sessionId(sessionId)
                .build();
    }
}
