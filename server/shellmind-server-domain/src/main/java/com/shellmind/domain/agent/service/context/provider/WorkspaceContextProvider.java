package com.shellmind.domain.agent.service.context.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkspaceContextProvider implements ContextProvider {

    private final Path workspace;

    public WorkspaceContextProvider(
            @Value("${shellmind.local.workspace:${user.home}}") String workspace) {
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "workspace";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId,
                                       String userId,
                                       String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> context = new HashMap<>();
        context.put("osInfo", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        context.put("currentUser", System.getProperty("user.name"));
        context.put("currentDirectory", workspace.toString());
        context.put("projectName", workspace.getFileName() == null ? workspace.toString() : workspace.getFileName().toString());
        context.put("projectRootPath", workspace.toString());
        return context;
    }
}
