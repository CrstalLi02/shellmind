package com.shellmind.trigger.http;

import com.shellmind.api.dto.*;
import com.shellmind.api.response.Response;
import com.shellmind.cases.agent.TerminalBindingCase;
import com.shellmind.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * SSH agent controller (deprecated; merged into AgentServiceController)
 * 
 * <p>The original bind_terminal / unbind_terminal / query_binding APIs
 * have moved to /api/v1/bind_terminal and related paths. This controller is kept only for compatibility.
 * 
 * @author waissh dev
 * @deprecated Merged into AgentServiceController; use the binding APIs under /api/v1/
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/agent")
@CrossOrigin(origins = "*")
@Deprecated
public class SshAgentController {

    @Resource
    private TerminalBindingCase terminalBindingCase;

    /**
     * Bind an SSH terminal to an agent session
     *
     * @param requestDTO bind request
     * @return bind result
     */
    @RequestMapping(value = "bind_terminal", method = RequestMethod.POST)
    public Response<BindTerminalResponseDTO> bindTerminal(@RequestBody BindTerminalRequestDTO requestDTO) {
        try {
            String chatSessionId = requestDTO.getChatSessionId();
            String terminalSessionId = requestDTO.getTerminalSessionId();

            log.info("Bind terminal session: chatSessionId={}, terminalSessionId={}", chatSessionId, terminalSessionId);

            if (chatSessionId == null || chatSessionId.isEmpty()) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("chatSessionId is required")
                        .build();
            }

            if (terminalSessionId == null || terminalSessionId.isEmpty()) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("terminalSessionId is required")
                        .build();
            }

            // Store the binding
            terminalBindingCase.bind(chatSessionId, terminalSessionId);

            BindTerminalResponseDTO response = BindTerminalResponseDTO.builder()
                    .chatSessionId(chatSessionId)
                    .terminalSessionId(terminalSessionId)
                    .bound(true)
                    .build();

            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();

        } catch (Exception e) {
            log.error("Failed to bind terminal session", e);
            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to bind: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Unbind the SSH terminal
     *
     * @param chatSessionId agent session ID
     * @return unbind result
     */
    @RequestMapping(value = "unbind_terminal", method = RequestMethod.POST)
    public Response<Void> unbindTerminal(@RequestParam("chatSessionId") String chatSessionId) {
        try {
            log.info("Unbind terminal session: chatSessionId={}", chatSessionId);

            terminalBindingCase.unbind(chatSessionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();

        } catch (Exception e) {
            log.error("Failed to unbind terminal session", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Failed to unbind: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Query the terminal bound to a session
     *
     * @param chatSessionId agent session ID
     * @return binding info
     */
    @RequestMapping(value = "query_binding", method = RequestMethod.GET)
    public Response<BindTerminalResponseDTO> queryBinding(@RequestParam("chatSessionId") String chatSessionId) {
        try {
            String terminalSessionId = terminalBindingCase.terminalOf(chatSessionId).orElse(null);

            if (terminalSessionId == null) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("No terminal is bound")
                        .data(BindTerminalResponseDTO.builder()
                                .chatSessionId(chatSessionId)
                                .bound(false)
                                .build())
                        .build();
            }

            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(BindTerminalResponseDTO.builder()
                            .chatSessionId(chatSessionId)
                            .terminalSessionId(terminalSessionId)
                            .bound(true)
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("Failed to query binding", e);
            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Query failed: " + e.getMessage())
                    .build();
        }
    }


}
