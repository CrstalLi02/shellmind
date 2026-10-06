package com.shellmind.cases.react;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.IAIAgentReActServiceCase;
import com.shellmind.cases.react.engine.AgentLoopExecutor;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.cases.react.node.RootNode;
import com.shellmind.domain.agent.service.engine.AgentLoopConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.shellmind.cases.react.channel.DiscardingClientChannel;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import jakarta.annotation.Resource;

/**
 * AI agent ReAct execution service implementation.
 *
 * <p>Responsibilities:
 * - streaming chat (SSE): create emitter → create dynamic context → walk the node chain
 * - plain chat (non-streaming): call the node chain directly
 *
 * <p>Node chain:
 * RootNode → AiCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4 14:45
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Resource
    private AgentLoopExecutor agentLoopExecutor;

    @Override
    public void chatStream(ChatRequestDTO requestDTO, ClientChannel emitter) {
        // 1. The channel is created by the interface layer (transport, timeout, response headers are decided there)

        // 2. Register emitter lifecycle callbacks so we notice client disconnect
        DefaultReActFactory.DynamicContext[] contextHolder = new DefaultReActFactory.DynamicContext[1];

        emitter.onCompletion(() -> {
            log.info("SSE connection completed - sessionId:{}", requestDTO.getSessionId());
            if (contextHolder[0] != null) {
                contextHolder[0].markCancelled("client_completed");
            }
        });

        emitter.onTimeout(() -> {
            log.warn("SSE connection timeout - sessionId:{}", requestDTO.getSessionId());
            if (contextHolder[0] != null) {
                contextHolder[0].setStopReason("idle_timeout");
                contextHolder[0].markCancelled("timeout");
            }
        });

        emitter.onError(ex -> {
            log.warn("SSE connection error - sessionId:{} reason:{}", requestDTO.getSessionId(), ex.getMessage());
            if (contextHolder[0] != null) {
                contextHolder[0].markCancelled("client_error");
            }
        });

        try {
            log.info("ReAct streaming chat start - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getTerminalSessionId());

            // 3. Initialize dynamic context
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();
            dynamicContext.setSessionId(requestDTO.getSessionId());
            dynamicContext.setUserId(requestDTO.getUserId());
            dynamicContext.setAgentId(requestDTO.getAgentId());
            dynamicContext.setTerminalSessionId(requestDTO.getTerminalSessionId());
            dynamicContext.setProjectContext(requestDTO.getProjectContext());
            contextHolder[0] = dynamicContext;

            // 4. Build AgentLoopConfig
            AgentLoopConfig loopConfig = buildLoopConfig(requestDTO);

            // 5. Run the node chain asynchronously (AgentLoopExecutor supervises; the chain executes)
            Thread streamThread = new Thread(() -> {
                try {
                    ReActResultDTO result = agentLoopExecutor.executeSupervised(
                            loopConfig,
                            dynamicContext,
                            requestDTO,
                            (req, ctx) -> rootNode.apply(req, ctx));

                    log.info("ReAct streaming chat complete - steps:{}, toolCalls:{}, stopReason:{}",
                            result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
                    // After a normal completion, close the SSE connection so the frontend reader.read() can return done
                    emitter.complete();
                } catch (Exception e) {
                    log.error("ReAct streaming chat exception", e);
                    try {
                        if (!dynamicContext.isCancelled()) {
                            try {
                                String errorMsg = e.getMessage() != null ? e.getMessage() : "unknownerror";
                                emitter.send("{\"event\":\"error\",\"content\":\"" + errorMsg.replace("\\", "").replace("\"", "'") + "\"}\n");
                            } catch (Exception ignored) {}
                            emitter.complete();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "react-stream-" + requestDTO.getSessionId());

            // Keep a thread reference so it can be interrupted later
            dynamicContext.setStreamThread(streamThread);

            streamThread.start();

            // 6. Start an SSE heartbeat daemon (every 15s) so proxies/browsers do not drop idle connections.
            //    Also refresh Agent LoopState last-active time so the idle checker does not false-timeout.
            Thread heartbeatThread = new Thread(() -> {
                while (!dynamicContext.isCancelled() && streamThread.isAlive()) {
                    try {
                        Thread.sleep(15_000L);
                        if (!dynamicContext.isCancelled() && streamThread.isAlive()) {
                            emitter.send("{\"event\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}\n");
                            // Heartbeat succeeded → refresh LoopState last-active time so the agent-layer idle checker does not false-kill
                            com.shellmind.domain.agent.service.engine.LoopState ls = dynamicContext.getLoopState();
                            if (ls != null) {
                                ls.touch();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // a failed heartbeat means the client has disconnected
                        log.warn("SSE global heartbeat send failed - sessionId:{}", requestDTO.getSessionId());
                        dynamicContext.markCancelled("heartbeat_send_failed");
                        break;
                    }
                }
            }, "react-heartbeat-" + requestDTO.getSessionId());
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            // Interrupt the heartbeat thread when the stream ends
            emitter.onCompletion(() -> heartbeatThread.interrupt());
            emitter.onTimeout(() -> heartbeatThread.interrupt());
            emitter.onError(ex -> heartbeatThread.interrupt());

        } catch (Exception e) {
            log.error("ReAct streaming chat init failed", e);
            emitter.completeWithError(e);
        }
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct plain chat start - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try {
            // Plain chat does not push intermediate events; return the result directly
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new DiscardingClientChannel())
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);

            return result.getContent() != null ? result.getContent() : "";

        } catch (Exception e) {
            log.error("ReAct plain chat exception", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Build AgentLoopConfig.
     * <p>Derive loop config from request params; later this can be overridden by agent YAML.
     */
    private AgentLoopConfig buildLoopConfig(ChatRequestDTO requestDTO) {
        return AgentLoopConfig.builder()
                .mode(AgentLoopConfig.AgentMode.AGENT)
                .maxRounds(50)
                .maxToolCallsPerRound(10)
                .maxTotalToolCalls(200)
                .maxAiRetries(3)
                .idleTimeoutMs(600_000L)
                .maxTokenBudget(200_000)
                .diminishingReturnsThreshold(3)
                .build();
    }

}
