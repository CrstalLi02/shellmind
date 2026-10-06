package com.shellmind.domain.ssh.service.terminal;

import com.shellmind.domain.ssh.adapter.port.ISshSessionPort;
import com.shellmind.domain.ssh.adapter.port.ITerminalSessionPort;
import com.shellmind.domain.ssh.model.entity.TerminalSessionEntity;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH terminal domain service
 * Follows single responsibility: terminal-session management is delegated to infrastructure
 *
 * @author waissh dev
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;

    /** sessionId -> terminal session entity */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionService;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("Opening terminal session connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. Ensure the SSH connection is established
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH connection is not established; connect first");
        }

        // 2. Drop stale sessions for the same connectionId (infrastructure already closed the channel; clear domain cache)
        sessionCache.entrySet().removeIf(entry -> {
            if (connectionId.equals(entry.getValue().getConnectionId())) {
                log.info("Clearing stale terminal session cache sessionId={} connectionId={}", entry.getKey(), connectionId);
                return true;
            }
            return false;
        });

        // 3. Open the terminal session via infrastructure
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 3. Create and cache the session entity
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(sessionId, entity);
        log.info("Terminal session created sessionId={}", sessionId);

        return entity;
    }

    /** Max time to wait for output after an agent command (ms). */
    private static final long COMMAND_EXEC_WAIT_MS = 5000;

    /** Poll interval while waiting for agent-command output (ms). */
    private static final long COMMAND_EXEC_CHECK_INTERVAL_MS = 100;

    @Override
    public String executeCommand(String sessionId, String command) {
        return executeCommand(sessionId, command, COMMAND_EXEC_WAIT_MS);
    }

    @Override
    public String executeCommand(String sessionId, String command, long waitTimeoutMs) {
        log.info("Agent executing command sessionId={} command={}", sessionId, command);

        // 1. Validate the session
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("Terminal session missing or closed");
        }

        // 2. Enable agent-only capture (output is written to both the main buffer and the agent buffer)
        // so frontend polling cannot steal agent-command output
        terminalSessionService.setAgentCapture(sessionId, true);

        try {
            // 3. Drain leftover output from the agent buffer
            terminalSessionService.readAgentBuffer(sessionId);

            // 4. Send the command to the terminal (command + \n triggers execution)
            terminalSessionService.write(sessionId, command + "\n");

            // 5. Update last-active time
            entity.touch();

            // 6. Wait for the command to finish: poll the agent buffer
            long deadline = System.currentTimeMillis() + Math.max(waitTimeoutMs, COMMAND_EXEC_CHECK_INTERVAL_MS);
            StringBuilder resultOutput = new StringBuilder();

            int emptyReadCount = 0;
            final int EMPTY_READ_THRESHOLD = 3;

            while (System.currentTimeMillis() < deadline) {
                // Read from the agent buffer, unaffected by frontend polling
                String chunk = terminalSessionService.readAgentBuffer(sessionId);
                if (chunk != null && !chunk.isEmpty()) {
                    resultOutput.append(chunk);
                    emptyReadCount = 0;
                } else {
                    emptyReadCount++;
                    String current = resultOutput.toString();
                    if (emptyReadCount >= EMPTY_READ_THRESHOLD && current.length() > 0) {
                        // Prompt-like output detected; command finished
                        if (current.matches(".*[#$][\\s\\r\\n].*") || current.contains("\r\n") && current.split("\r\n").length > 2) {
                            break;
                        }
                    }
                }
                try {
                    Thread.sleep(COMMAND_EXEC_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // One last read so nothing is missed
            String finalChunk = terminalSessionService.readAgentBuffer(sessionId);
            if (finalChunk != null && !finalChunk.isEmpty()) {
                resultOutput.append(finalChunk);
            }

            String output = resultOutput.toString();
            log.info("Command finished sessionId={} outputLength={} output={}", sessionId, output.length(),
                    output.length() > 300 ? output.substring(0, 300) + "..." : output);

            return output;

        } finally {
            // 7. Disable agent capture
            terminalSessionService.setAgentCapture(sessionId, false);
        }
    }

    @Override
    public void resizeTerminal(String sessionId, int cols, int rows) {
        log.debug("Resizing terminal sessionId={} cols={} rows={}", sessionId, cols, rows);

        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("Terminal session missing or closed");
        }

        terminalSessionService.resize(sessionId, cols, rows);

        entity.setCols(cols);
        entity.setRows(rows);
        entity.touch();
    }

    @Override
    public TerminalSessionEntity getTerminalSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    @Override
    public void closeTerminal(String sessionId) {
        log.info("Closing terminal session sessionId={}", sessionId);

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionService.closeSession(sessionId);
            log.info("Terminal session closed sessionId={}", sessionId);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionCache.containsKey(sessionId);
    }

    @Override
    public String readTerminal(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("Terminal session missing or closed");
        }
        return terminalSessionService.read(sessionId);
    }

    @Override
    public void writeTerminal(String sessionId, String input) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("Terminal session missing or closed");
        }
        terminalSessionService.write(sessionId, input);
        entity.touch();
    }

    @Override
    public String executeCommandStreaming(String sessionId, String command, long waitTimeoutMs,
                                          java.util.function.Consumer<String> chunkCallback) {
        log.info("Agent streaming command sessionId={} command={}", sessionId, command);

        // 1. Validate the session
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("Terminal session missing or closed");
        }

        // 2. Enable agent-only capture
        terminalSessionService.setAgentCapture(sessionId, true);

        try {
            // 3. Drain leftover output
            terminalSessionService.readAgentBuffer(sessionId);

            // 4. Send the command
            terminalSessionService.write(sessionId, command + "\n");
            entity.touch();

            // 5. Poll the agent buffer and callback each chunk in real time
            long deadline = System.currentTimeMillis() + Math.max(waitTimeoutMs, COMMAND_EXEC_CHECK_INTERVAL_MS);
            StringBuilder resultOutput = new StringBuilder();

            int emptyReadCount = 0;
            final int EMPTY_READ_THRESHOLD = 3;

            while (System.currentTimeMillis() < deadline) {
                String chunk = terminalSessionService.readAgentBuffer(sessionId);
                if (chunk != null && !chunk.isEmpty()) {
                    resultOutput.append(chunk);
                    emptyReadCount = 0;
                    // Push the chunk immediately
                    try {
                        chunkCallback.accept(chunk);
                    } catch (Exception ce) {
                        log.debug("chunkCallback exception (does not affect execution)", ce);
                    }
                } else {
                    emptyReadCount++;
                    String current = resultOutput.toString();
                    if (emptyReadCount >= EMPTY_READ_THRESHOLD && current.length() > 0) {
                        if (current.matches(".*[#$][\s\r\n].*") || current.contains("\r\n") && current.split("\r\n").length > 2) {
                            break;
                        }
                    }
                }
                try {
                    Thread.sleep(COMMAND_EXEC_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // One last read
            String finalChunk = terminalSessionService.readAgentBuffer(sessionId);
            if (finalChunk != null && !finalChunk.isEmpty()) {
                resultOutput.append(finalChunk);
                try {
                    chunkCallback.accept(finalChunk);
                } catch (Exception ce) {
                    log.debug("chunkCallback exception (does not affect execution)", ce);
                }
            }

            String output = resultOutput.toString();
            log.info("Streaming command finished sessionId={} outputLength={}", sessionId, output.length());
            return output;

        } finally {
            terminalSessionService.setAgentCapture(sessionId, false);
        }
    }

}
