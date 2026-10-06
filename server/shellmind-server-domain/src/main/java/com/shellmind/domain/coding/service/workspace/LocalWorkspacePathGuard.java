package com.shellmind.domain.coding.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Profile("local")
public class LocalWorkspacePathGuard implements WorkspacePathGuard {

    private final Path workspace;

    public LocalWorkspacePathGuard(@Value("${shellmind.local.workspace:${user.home}}") String workspace) {
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
    }

    @Override
    public Path resolve(Path requestWorkspace, String input) {
        Path activeWorkspace = requestWorkspace != null ? requestWorkspace : workspace;
        if (input == null || input.isBlank()) {
            return activeWorkspace;
        }
        Path path = activeWorkspace.resolve(input).normalize();
        if (!path.startsWith(activeWorkspace)) {
            throw new IllegalArgumentException("Path must be inside the workspace: " + activeWorkspace);
        }
        return path;
    }
}
