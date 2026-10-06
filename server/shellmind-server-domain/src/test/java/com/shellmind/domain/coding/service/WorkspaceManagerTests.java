package com.shellmind.domain.coding.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerTests {

    private final WorkspaceManager workspaceManager = new WorkspaceManager();

    @TempDir
    Path projectRoot;

    @Test
    void resolveGitWorkspace() throws IOException {
        Files.createDirectories(projectRoot.resolve(".git"));

        WorkspaceManager.WorkspaceState state = workspaceManager.resolve(projectRoot);

        assertEquals(projectRoot.toAbsolutePath().normalize(), state.root());
        assertTrue(state.gitRepository());
    }
}
