package com.shellmind.cases.agent;

import com.shellmind.domain.agent.service.run.TerminalBindingRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Use case for binding a chat session to an SSH terminal.
 */
@Service
public class TerminalBindingCase {

    @Resource
    private TerminalBindingRegistry terminalBindingRegistry;

    public void bind(String chatSessionId, String terminalSessionId) {
        terminalBindingRegistry.bind(chatSessionId, terminalSessionId);
    }

    public void unbind(String chatSessionId) {
        terminalBindingRegistry.unbind(chatSessionId);
    }

    public Optional<String> terminalOf(String chatSessionId) {
        return terminalBindingRegistry.terminalOf(chatSessionId);
    }
}
