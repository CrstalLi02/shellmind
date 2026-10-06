package com.shellmind.trigger.http;

import com.shellmind.api.dto.*;
import com.shellmind.api.response.Response;
import com.shellmind.cases.ssh.SshTerminalCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * SSH terminal HTTP controller
 * Provides open, raw I/O, resize, and close for terminal sessions
 *
 * @author waissh dev
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/terminal")
@CrossOrigin(origins = "*")
public class SshTerminalController {

    @Resource
    private SshTerminalCase sshTerminalCase;

    @RequestMapping(value = "open", method = RequestMethod.POST)
    public Response<TerminalOpenResponseDTO> openTerminal(@RequestBody TerminalOpenRequestDTO requestDTO) {
        try {
            log.info("Open terminal session connectionId={}", requestDTO.getConnectionId());

            int cols = requestDTO.getCols() != null ? requestDTO.getCols() : 120;
            int rows = requestDTO.getRows() != null ? requestDTO.getRows() : 24;

            // Return initial output (MOTD, etc.) together with the open result
            TerminalOpenResponseDTO response = sshTerminalCase.open(requestDTO.getConnectionId(), cols, rows);

            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Invalid parameters while opening terminal session: {}", e.getMessage());
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to open terminal session", e);
            return Response.<TerminalOpenResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to open terminal session: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "exec", method = RequestMethod.POST)
    public Response<TerminalExecResponseDTO> execCommand(@RequestBody TerminalExecRequestDTO requestDTO) {
        try {
            String output = sshTerminalCase.exec(requestDTO.getSessionId(), requestDTO.getCommand());

            TerminalExecResponseDTO response = TerminalExecResponseDTO.builder()
                    .output(output)
                    .build();

            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while executing command: {}", e.getMessage());
            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to execute command sessionId={}", requestDTO.getSessionId(), e);
            return Response.<TerminalExecResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to execute command: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "write", method = RequestMethod.POST)
    public Response<Void> writeToTerminal(@RequestBody TerminalWriteRequestDTO requestDTO) {
        try {
            sshTerminalCase.write(requestDTO.getSessionId(), requestDTO.getInput());
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while writing to terminal: {}", e.getMessage());
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to write to terminal sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to write to terminal: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "read", method = RequestMethod.GET)
    public Response<TerminalReadResponseDTO> readFromTerminal(@RequestParam("sessionId") String sessionId) {
        try {
            String output = sshTerminalCase.read(sessionId);
            TerminalReadResponseDTO response = TerminalReadResponseDTO.builder()
                    .output(output != null ? output : "")
                    .build();
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters while reading terminal: {}", e.getMessage());
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Failed to read from terminal sessionId={}", sessionId, e);
            return Response.<TerminalReadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to read from terminal: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "resize", method = RequestMethod.POST)
    public Response<Void> resizeTerminal(@RequestBody TerminalResizeRequestDTO requestDTO) {
        try {
            sshTerminalCase.resize(requestDTO.getSessionId(), requestDTO.getCols(), requestDTO.getRows());

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("Failed to resize terminal sessionId={}", requestDTO.getSessionId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to resize terminal: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "close", method = RequestMethod.POST)
    public Response<Void> closeTerminal(@RequestParam("sessionId") String sessionId) {
        try {
            log.info("Close terminal session sessionId={}", sessionId);
            sshTerminalCase.close(sessionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("Failed to close terminal session sessionId={}", sessionId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to close terminal session: " + e.getMessage())
                    .build();
        }
    }

}
