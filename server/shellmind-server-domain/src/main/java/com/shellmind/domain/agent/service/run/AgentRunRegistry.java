package com.shellmind.domain.agent.service.run;

import com.shellmind.domain.agent.adapter.port.ClientChannel;
import com.shellmind.domain.shared.model.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of in-flight agent runs: session ID → run context + client channel.
 * <p>
 * The use-case layer registers at the start of a run and unregisters at the end; tools, command
 * dispatch, and progress notifications look up by session ID, so concurrent runs in different
 * sessions are isolated and do not depend on the executing thread.
 * Sub-agent child sessions are registered via {@link #registerChild} and reuse the parent session's client channel.
 */
@Slf4j
@Component
public class AgentRunRegistry {

    /** Registry entry: channel may be null (e.g. non-streaming chat) */
    public record ActiveRun(RunContext context, ClientChannel channel) {
    }

    private final Map<String, ActiveRun> runs = new ConcurrentHashMap<>();

    public void register(RunContext context, ClientChannel channel) {
        runs.put(context.sessionId(), new ActiveRun(context, channel));
    }

    /** Register a child session: the channel is inherited from the parent (null if the parent session is missing) */
    public void registerChild(RunContext childContext) {
        ClientChannel parentChannel = Optional.ofNullable(childContext.parentSessionId())
                .map(runs::get)
                .map(ActiveRun::channel)
                .orElse(null);
        runs.put(childContext.sessionId(), new ActiveRun(childContext, parentChannel));
    }

    public void unregister(String sessionId) {
        if (sessionId != null) {
            runs.remove(sessionId);
        }
    }

    public Optional<RunContext> context(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(runs.get(sessionId)).map(ActiveRun::context);
    }

    /**
     * Get the run context, or throw if missing (tools are only invoked inside a registered run).
     */
    public RunContext requireContext(String sessionId) {
        return context(sessionId).orElseThrow(() ->
                new IllegalStateException("Run context does not exist (session is not in a run): " + sessionId));
    }

    public Optional<ClientChannel> channel(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(runs.get(sessionId)).map(ActiveRun::channel);
    }

    /** Whether any in-flight run holds a client channel (used for status queries) */
    public boolean hasClientChannel() {
        return runs.values().stream().anyMatch(run -> run.channel() != null);
    }

    public boolean isActive(String sessionId) {
        return sessionId != null && runs.containsKey(sessionId);
    }
}
