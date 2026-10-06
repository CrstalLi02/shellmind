package com.shellmind.infrastructure.agent.runtime;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;
import com.shellmind.domain.agent.model.valobj.runtime.InputPart;
import com.shellmind.domain.agent.model.valobj.runtime.ToolResponse;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link AgentRuntime} implementation based on Google ADK Runner.
 */
@Slf4j
@Component
public class AdkAgentRuntime implements AgentRuntime {

    @Lazy
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /** Lazy: the registry depends on the assembly chain, and assembled tools depend on this runtime (sub-agents) */
    @Lazy
    @Resource
    private IDynamicAgentRegistry dynamicAgentRegistry;

    @Override
    public Iterator<AgentRuntimeEvent> run(AgentRunRequest request) {
        Runner runner = register(request.agentId()).getRunner();
        Content content = Content.builder().role("user").parts(toParts(request.parts())).build();

        Flowable<Event> events = request.streaming()
                ? runner.runAsync(request.userId(), request.sessionId(), content,
                        RunConfig.builder()
                                .streamingMode(RunConfig.StreamingMode.SSE)
                                .autoCreateSession(true)
                                .build())
                : runner.runAsync(request.userId(), request.sessionId(), content);

        Iterator<Event> source = events.blockingIterable().iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public AgentRuntimeEvent next() {
                return toDomainEvent(source.next());
            }
        };
    }

    @Override
    public String resolveAgent(String sourceAgentId, Long modelId) {
        return dynamicAgentRegistry.registerAgentForModel(sourceAgentId, modelId);
    }

    @Override
    public void evictModelAgent(Long modelId) {
        dynamicAgentRegistry.evict(modelId);
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO register = register(agentId);
        // Create a new ADK session on every call so sessions stay isolated
        String sessionKey = userId + "_" + System.currentTimeMillis();
        Session session = register.getRunner().sessionService()
                .createSession(register.getAppName(), userId, null, sessionKey)
                .blockingGet();
        return session.id();
    }

    @Override
    public boolean isRegistered(String agentId) {
        try {
            return agentId != null && defaultArmoryFactory.getAiAgentRegisterVO(agentId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private AiAgentRegisterVO register(String agentId) {
        AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (register == null) {
            throw new IllegalStateException("Agent not found: " + agentId);
        }
        return register;
    }

    private List<Part> toParts(List<InputPart> inputs) {
        List<Part> parts = new ArrayList<>(inputs.size());
        for (InputPart input : inputs) {
            if (input.isText()) {
                parts.add(Part.builder().text(input.text()).build());
            } else if (input.data() != null) {
                parts.add(Part.fromBytes(input.data(), input.mimeType()));
            } else if (input.uri() != null) {
                parts.add(Part.fromUri(input.uri(), input.mimeType()));
            }
        }
        return parts;
    }

    private AgentRuntimeEvent toDomainEvent(Event event) {
        String text = event.content()
                .map(content -> content.parts().orElse(List.of()).stream()
                        .map(part -> part.text().orElse(""))
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.joining()))
                .orElse("");

        List<ToolResponse> toolResponses = new ArrayList<>();
        for (FunctionResponse response : event.functionResponses()) {
            toolResponses.add(new ToolResponse(
                    response.id().orElse(null),
                    response.name().orElse("unknown_tool"),
                    response.response().orElse(null)));
        }

        int functionCalls = event.functionCalls() == null ? 0 : event.functionCalls().size();
        log.debug("[AdkRuntime] event: final={}, textLen={}, functionCalls={}, functionResponses={}",
                event.finalResponse(), text.length(), functionCalls, toolResponses.size());
        return new AgentRuntimeEvent(text, event.stringifyContent(), functionCalls, toolResponses);
    }
}
