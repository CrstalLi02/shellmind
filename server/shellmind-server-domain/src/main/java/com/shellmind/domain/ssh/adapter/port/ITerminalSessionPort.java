package com.shellmind.domain.ssh.adapter.port;

/**
 * Terminal session port.
 * Manages SSH terminal sessions: open / write / read / resize / close.
 *
 * @author waissh dev
 */
public interface ITerminalSessionPort {

    /**
     * Open a terminal session.
     *
     * @param connectionId SSH connection ID
     * @param cols         terminal columns
     * @param rows         terminal rows
     * @return session ID
     */
    String openTerminal(String connectionId, int cols, int rows);

    /**
     * Write a command to the terminal.
     *
     * @param sessionId session ID
     * @param command   command text
     */
    void write(String sessionId, String command);

    /**
     * Read terminal output.
     *
     * @param sessionId session ID
     * @return terminal output
     */
    String read(String sessionId);

    /**
     * Enable or disable agent-only capture mode.
     * When enabled, output is written to both the main buffer and the agent-only buffer.
     *
     * @param sessionId session ID
     * @param capture   true=enable capture, false=disable capture
     */
    void setAgentCapture(String sessionId, boolean capture);

    /**
     * Read (and clear) the agent-only buffer.
     *
     * @param sessionId session ID
     * @return agent-only buffer contents
     */
    String readAgentBuffer(String sessionId);

    /**
     * Resize the terminal.
     *
     * @param sessionId session ID
     * @param cols      new column count
     * @param rows      new row count
     */
    void resize(String sessionId, int cols, int rows);

    /**
     * Close a terminal session.
     *
     * @param sessionId session ID
     */
    void closeSession(String sessionId);

    /**
     * Check whether a session exists.
     *
     * @param sessionId session ID
     * @return whether it exists
     */
    boolean sessionExists(String sessionId);

}
