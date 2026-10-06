package com.shellmind.domain.coding.service.workspace;

import java.nio.file.Path;

public interface WorkspacePathGuard {

    /**
     * Resolve a tool-supplied path to an absolute path and enforce workspace bounds.
     *
     * @param activeWorkspace workspace of the current run (RunContext#workspace); null uses the default workspace
     * @param input           path supplied by the tool
     */
    Path resolve(Path activeWorkspace, String input);
}
