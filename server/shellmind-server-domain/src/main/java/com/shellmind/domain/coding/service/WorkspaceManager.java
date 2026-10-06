package com.shellmind.domain.coding.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class WorkspaceManager {

    public WorkspaceState resolve(Path requestedRoot) throws IOException {
        Path root = requestedRoot.toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IOException("Workspace does not exist: " + root);
        }

        Path gitDirectory = root.resolve(".git");
        if (!Files.exists(gitDirectory)) {
            throw new IOException("Workspace is not a Git repository: " + root);
        }

        return new WorkspaceState(root, Files.exists(gitDirectory));
    }

    public record WorkspaceState(Path root, boolean gitRepository) {
    }
}
