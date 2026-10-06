package com.shellmind.trigger.http;

import com.shellmind.api.IAgentService;
import com.shellmind.api.dto.*;
import com.shellmind.api.response.Response;
import com.shellmind.cases.IAIAgentReActServiceCase;
import com.shellmind.cases.agent.AgentRunCase;
import com.shellmind.cases.agent.AgentSessionCase;
import com.shellmind.cases.agent.TerminalBindingCase;
import com.shellmind.cases.command.LocalCommandCase;
import com.shellmind.trigger.http.sse.SseClientChannel;
import com.shellmind.types.enums.ResponseCode;
import com.shellmind.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 *
 * @author xiaofuge bugstack.cn
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private AgentSessionCase agentSessionCase;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

    @Resource
    private TerminalBindingCase terminalBindingCase;

    @Resource
    private LocalCommandCase localCommandCase;

    @Resource
    private AgentRunCase agentRunCase;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("Query agent config list");

            List<AiAgentConfigResponseDTO> responseDTOS = agentSessionCase.queryAgentConfigs();

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("Exception while querying agent config list", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("Failed to query agent config list", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("Create session agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = agentSessionCase.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("Exception while querying agent config list", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create session agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("Agent chat agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(agentSessionCase.chat(requestDTO));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("Agent chat exception", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("Agent chat failed agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSH terminal-binding APIs (merged from SshAgentController)
    // ═══════════════════════════════════════════════════════════════

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



    // ═══════════════════════════════════════════════════════════════
    //  Local command-result callback APIs (client → server)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Client posts local command execution results
     *
     * <p>After the client receives an execute_local_command SSE event, it runs the local command
     * and posts the result back to the server through this endpoint.
     *
     * @param result command execution result
     * @return processing status
     */
    @RequestMapping(value = "tool_result", method = RequestMethod.POST)
    public Response<Void> receiveToolResult(@RequestBody CommandResultDTO result) {
        try {
            log.info("Received local command result: cmdId={}, sessionId={}, status={}, exitCode={}",
                    result.getCmdId(), result.getSessionId(), result.getStatus(), result.getExitCode());

            localCommandCase.complete(result);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Result received")
                    .build();

        } catch (Exception e) {
            log.error("Failed to process local command result: cmdId={}", result.getCmdId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Processing failed: " + e.getMessage())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2: GET polling endpoints (client pulls results)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Client polls the execution result for a cmdId
     *
     * <p>GET cache API: the client periodically calls this endpoint to check whether the result is ready.
     * The result is removed from the cache after it is consumed (read once).
     *
     * @param cmdId command ID
     * @return cached execution result, or null if not ready
     */
    @RequestMapping(value = "tool_result/pending", method = RequestMethod.GET)
    public Response<CommandResultDTO> pollToolResult(@RequestParam("cmdId") String cmdId) {
        try {
            CommandResultDTO result = localCommandCase.poll(cmdId);
            if (result != null) {
                log.info("[GET Cache] Returning cached result: cmdId={}, status={}", cmdId, result.getStatus());
            }
            return Response.<CommandResultDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result)
                    .build();
        } catch (Exception e) {
            log.error("[GET Cache] Failed to poll result: cmdId={}", cmdId, e);
            return Response.<CommandResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Polling failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * After reconnecting, the client batch-pulls all cached results
     *
     * <p>After the client detects an SSE drop and reconnects, it calls this endpoint for results missed while disconnected.
     * Results are removed from the cache after they are consumed.
     *
     * @return all cached results (cmdId → CommandResultDTO)
     */
    @RequestMapping(value = "tool_result/pending_all", method = RequestMethod.GET)
    public Response<Map<String, CommandResultDTO>> pollAllToolResults() {
        try {
            Map<String, CommandResultDTO> results = localCommandCase.pollAll();
            if (!results.isEmpty()) {
                log.info("[GET Cache] Batch-returning {} cached result(s)", results.size());
            }
            return Response.<Map<String, CommandResultDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(results)
                    .build();
        } catch (Exception e) {
            log.error("[GET Cache] Failed to batch-poll results", e);
            return Response.<Map<String, CommandResultDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Batch polling failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get local-command dispatch status
     *
     * @return dispatcher status
     */
    @RequestMapping(value = "command_status", method = RequestMethod.GET)
    public Response<Map<String, Object>> getCommandStatus() {
        Map<String, Object> status = localCommandCase.status();
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(status)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Existing APIs
    // ═══════════════════════════════════════════════════════════════

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("ReAct streaming chat agentId:{} userId:{} sessionId:{} terminalSessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(),
                    requestDTO.getTerminalSessionId(), requestDTO.getMessage());

            // Create a session if sessionId is not specified
            agentSessionCase.ensureSession(requestDTO);

            // Route to the ReAct service (case layer); the channel is created by the interface layer
            SseClientChannel channel = SseClientChannel.open();
            reactServiceCase.chatStream(requestDTO, channel);
            return channel.emitter();
        } catch (Exception e) {
            log.error("ReAct streaming chat failed", e);
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            emitter.completeWithError(e);
            return emitter;
        }
    }

    @RequestMapping(value = "agent_run/{runId}", method = RequestMethod.GET)
    @Override
    public Response<AgentRunResponseDTO> queryAgentRun(@PathVariable("runId") String runId) {
        try {
            AgentRunResponseDTO run = agentRunCase.queryRun(runId).orElse(null);
            if (run == null) {
                return Response.<AgentRunResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("Run record does not exist")
                        .build();
            }
            return success(run);
        } catch (Exception e) {
            log.error("Failed to query run details runId:{}", runId, e);
            return Response.<AgentRunResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "agent_run/session/{sessionId}", method = RequestMethod.GET)
    @Override
    public Response<List<AgentRunResponseDTO>> queryAgentRunsBySession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            List<AgentRunResponseDTO> runs = agentRunCase.queryRunsBySession(sessionId, limit);
            return success(runs);
        } catch (Exception e) {
            log.error("Failed to query session runs sessionId:{}", sessionId, e);
            return Response.<List<AgentRunResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "agent_run/session/{sessionId}/resumable", method = RequestMethod.GET)
    public Response<AgentRunResponseDTO> queryResumableRun(@PathVariable("sessionId") String sessionId) {
        try {
            AgentRunResponseDTO latestResumable = agentRunCase.latestResumable(sessionId).orElse(null);

            if (latestResumable == null) {
                return Response.<AgentRunResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("No resumable run")
                        .build();
            }
            return success(latestResumable);
        } catch (Exception e) {
            log.error("Failed to query resumable run sessionId:{}", sessionId, e);
            return Response.<AgentRunResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "agent_run/{runId}/resume_context", method = RequestMethod.GET)
    public Response<RunResumeResponseDTO> queryRunResumeContext(@PathVariable("runId") String runId) {
        try {
            RunResumeResponseDTO dto = agentRunCase.resumeContext(runId).orElse(null);
            if (dto == null) {
                return Response.<RunResumeResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("Run does not exist")
                        .build();
            }
            return success(dto);
        } catch (Exception e) {
            log.error("Failed to query resume context runId:{}", runId, e);
            return Response.<RunResumeResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

}
