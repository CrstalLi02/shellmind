package com.shellmind.api;

import com.shellmind.api.dto.*;
import com.shellmind.api.response.Response;

/**
 * SSH terminal remote API
 *
 * @author waissh dev
 */
public interface ISshTerminalService {

    /**
     * Open a terminal session
     */
    Response<TerminalOpenResponseDTO> openTerminal(TerminalOpenRequestDTO requestDTO);

    /**
     * Execute a command (full-line mode)
     */
    Response<TerminalExecResponseDTO> execCommand(TerminalExecRequestDTO requestDTO);

    /**
     * Write raw input to the terminal (byte-by-byte; the shell handles echo)
     */
    Response<Void> writeToTerminal(TerminalWriteRequestDTO requestDTO);

    /**
     * Read buffered terminal output (polling mode)
     */
    Response<TerminalReadResponseDTO> readFromTerminal(String sessionId);

    /**
     * Resize the terminal
     */
    Response<Void> resizeTerminal(TerminalResizeRequestDTO requestDTO);

    /**
     * Close a terminal session
     */
    Response<Void> closeTerminal(String sessionId);

}
