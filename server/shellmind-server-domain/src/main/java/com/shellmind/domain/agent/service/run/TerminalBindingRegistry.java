package com.shellmind.domain.agent.service.run;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binding of a chat session → SSH terminal session (explicitly maintained by the client via bind_terminal).
 * <p>
 * When a chat request carries terminalSessionId, that value wins; otherwise this binding is used.
 */
@Component
public class TerminalBindingRegistry {

    private final Map<String, String> bindings = new ConcurrentHashMap<>();

    public void bind(String chatSessionId, String terminalSessionId) {
        if (chatSessionId != null && !chatSessionId.isBlank()
                && terminalSessionId != null && !terminalSessionId.isBlank()) {
            bindings.put(chatSessionId, terminalSessionId);
        }
    }

    public void unbind(String chatSessionId) {
        if (chatSessionId != null) {
            bindings.remove(chatSessionId);
        }
    }

    public Optional<String> terminalOf(String chatSessionId) {
        return chatSessionId == null ? Optional.empty() : Optional.ofNullable(bindings.get(chatSessionId));
    }
}
