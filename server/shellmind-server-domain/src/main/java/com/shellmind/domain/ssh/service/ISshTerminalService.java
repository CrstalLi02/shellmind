package com.shellmind.domain.ssh.service;

import com.shellmind.domain.ssh.model.entity.TerminalSessionEntity;

/**
 * SSH terminal domain service.
 * Defines core terminal-session operations.
 *
 * @author waissh dev
 */
public interface ISshTerminalService {

    /**
     * Open a terminal session.
     *
     * @param connectionId SSH connection ID
     * @param cols          terminal columns
     * @param rows          terminal rows
     * @return terminal session entity
     */
    TerminalSessionEntity openTerminal(String connectionId, int cols, int rows);

    /**
     * Execute a command and return its output.
     *
     * @param sessionId session ID
     * @param command    command text
     * @return terminal output after the command
     */
    String executeCommand(String sessionId, String command);

    /**
     * Execute a command and return its output (custom wait timeout).
     *
     * @param sessionId session ID
     * @param command command text
     * @param waitTimeoutMs max wait time in milliseconds
     * @return terminal output after the command
     */
    String executeCommand(String sessionId, String command, long waitTimeoutMs);

    /**
     * Resize the terminal.
     *
     * @param sessionId session ID
     * @param cols       new column count
     * @param rows       new row count
     */
    void resizeTerminal(String sessionId, int cols, int rows);

    /**
     * Get a terminal session.
     *
     * @param sessionId session ID
     * @return terminal session entity
     */
    TerminalSessionEntity getTerminalSession(String sessionId);

    /**
     * Close a terminal session.
     *
     * @param sessionId session ID
     */
    void closeTerminal(String sessionId);

    /**
     * Check whether a session exists.
     *
     * @param sessionId session ID
     * @return whether it exists
     */
    boolean sessionExists(String sessionId);

    /**
     * Read current terminal output (does not execute a command; used to sync state).
     *
     * @param sessionId session ID
     * @return current terminal output
     */
    String readTerminal(String sessionId);

    /**
     * Write raw input to the terminal (byte-by-byte; the shell handles echo).
     *
     * @param sessionId session ID
     * @param input     raw input data
     */
    void writeTerminal(String sessionId, String input);

    /**
     * Execute a command and push each output chunk in real time (streaming).
     * <p>Unlike executeCommand, this method invokes the callback as soon as each chunk is polled,
     * which is useful when command progress should be shown live (long commands, builds, installs).
     *
     * @param sessionId    session ID
     * @param command       command text
     * @param waitTimeoutMs max wait time in milliseconds
     * @param chunkCallback callback for each output chunk (only non-empty chunks)
     * @return full terminal output after the command (for the caller as the final result)
     */
    String executeCommandStreaming(String sessionId, String command, long waitTimeoutMs,
                                   java.util.function.Consumer<String> chunkCallback);

}
