package com.shellmind.cases.ssh;

import com.shellmind.api.dto.TerminalOpenResponseDTO;
import com.shellmind.domain.ssh.model.entity.TerminalSessionEntity;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * SSH terminal-session use case: open, raw I/O, resize, close.
 */
@Service
public class SshTerminalCase {

    @Resource
    private ISshTerminalService sshTerminalService;

    /**
     * Open the terminal and return the shell's initial output (Last login + MOTD + prompt) as initialOutput,
     * so the frontend does not poll for the initial output (which caused intermittent display).
     */
    public TerminalOpenResponseDTO open(String connectionId, int cols, int rows) {
        TerminalSessionEntity entity = sshTerminalService.openTerminal(connectionId, cols, rows);
        return TerminalOpenResponseDTO.builder()
                .sessionId(entity.getSessionId())
                .connectionId(entity.getConnectionId())
                .initialOutput(drainInitialOutput(entity.getSessionId()))
                .build();
    }

    public String exec(String sessionId, String command) {
        return sshTerminalService.executeCommand(sessionId, command);
    }

    public void write(String sessionId, String input) {
        sshTerminalService.writeTerminal(sessionId, input);
    }

    public String read(String sessionId) {
        return sshTerminalService.readTerminal(sessionId);
    }

    public void resize(String sessionId, int cols, int rows) {
        sshTerminalService.resizeTerminal(sessionId, cols, rows);
    }

    public void close(String sessionId) {
        sshTerminalService.closeTerminal(sessionId);
    }

    /**
     * openTerminal already waited for first data + 200ms; here we only drain the buffer;
     * do not convert newlines; xterm.js handles \r and \n itself
     */
    private String drainInitialOutput(String sessionId) {
        String output = sshTerminalService.readTerminal(sessionId);
        if (output == null || output.isEmpty()) {
            return "";
        }

        // Drain once more so leftover data is included
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        String more = sshTerminalService.readTerminal(sessionId);
        if (more != null && !more.isEmpty()) {
            output += more;
        }
        return output;
    }
}
