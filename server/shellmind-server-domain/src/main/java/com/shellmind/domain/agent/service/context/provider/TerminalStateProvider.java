package com.shellmind.domain.agent.service.context.provider;

import com.shellmind.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.shellmind.domain.ssh.model.entity.SshConnectionEntity;
import com.shellmind.domain.ssh.model.entity.TerminalSessionEntity;
import com.shellmind.domain.ssh.service.ISshTerminalService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalStateProvider implements ContextProvider {
    @Resource
    private ISshTerminalService sshTerminalService;
    @Resource
    private ISshConnectionRepository sshConnectionRepository;

    /** Cache: terminalSessionId → {osInfo, currentUser, uptime} (stable within a session) */
    private final Map<String, Map<String, String>> staticCache = new ConcurrentHashMap<>();

    @Override public String getName() { return "terminal-state"; }
    @Override public int getOrder() { return 10; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return result;
        }

        // Inject SSH connection info (name, host, port, user)
        injectConnectionInfo(result, terminalSessionId);

        // Cache static info (osInfo, whoami, uptime do not change within a terminal session)
        Map<String, String> cached = staticCache.computeIfAbsent(terminalSessionId, id -> {
            Map<String, String> m = new HashMap<>();
            m.put("osInfo", safeExec(id, "uname -srm"));
            m.put("currentUser", safeExec(id, "whoami"));
            m.put("uptime", safeExec(id, "uptime -p 2>/dev/null || uptime"));
            return m;
        });

        result.put("osInfo", cached.get("osInfo"));
        result.put("currentUser", cached.get("currentUser"));
        result.put("uptime", cached.get("uptime"));

        // Always re-fetch pwd (the user may have cd'd)
        result.put("currentDirectory", safeExec(terminalSessionId, "pwd"));

        return result;
    }

    /** Clear the cache for a terminal session (called on disconnect) */
    public void invalidateCache(String terminalSessionId) {
        staticCache.remove(terminalSessionId);
    }

    /**
     * Inject SSH connection info into context via
     * terminalSessionId → TerminalSessionEntity → connectionId → SshConnectionEntity.
     */
    private void injectConnectionInfo(Map<String, Object> result, String terminalSessionId) {
        try {
            TerminalSessionEntity sessionEntity = sshTerminalService.getTerminalSession(terminalSessionId);
            if (sessionEntity == null || sessionEntity.getConnectionId() == null) {
                return;
            }
            SshConnectionEntity connEntity = sshConnectionRepository.queryConnectionById(sessionEntity.getConnectionId());
            if (connEntity == null) {
                return;
            }
            result.put("sshConnectionName", connEntity.getConnectionName());
            result.put("sshHost", connEntity.getHost());
            result.put("sshPort", connEntity.getPort());
            result.put("sshUsername", connEntity.getUsername());
            result.put("sshConnectionId", connEntity.getConnectionId());
        } catch (Exception e) {
            // Failure to load connection info must not block other context injection
        }
    }

    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String res = sshTerminalService.executeCommand(terminalSessionId, cmd);
            return res != null ? res.trim() : "";
        } catch (Exception e) { 
            return ""; 
        }
    }
}
