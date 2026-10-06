package com.shellmind.domain.coding.service.workspace;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Profile("!local")
public class OpenWorkspacePathGuard implements WorkspacePathGuard {

    @Override
    public Path resolve(Path activeWorkspace, String input) {
        return Paths.get(input);
    }
}
