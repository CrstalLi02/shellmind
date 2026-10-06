package com.shellmind.domain.coding.model.valobj;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Spec for running an external process.
 *
 * @param command             program and arguments (not passed through a shell)
 * @param workingDirectory    working directory
 * @param timeout             timeout
 * @param environmentDefaults default environment variables (existing same-name variables are not overwritten)
 * @param maxOutputLines      max output lines to keep (stdout and stderr merged); extra lines are discarded but still drained
 */
public record ProcessSpec(List<String> command,
                          Path workingDirectory,
                          Duration timeout,
                          Map<String, String> environmentDefaults,
                          int maxOutputLines) {

    public ProcessSpec {
        command = List.copyOf(command);
        environmentDefaults = environmentDefaults == null ? Map.of() : Map.copyOf(environmentDefaults);
    }

    public static ProcessSpec of(Path workingDirectory, Duration timeout, String... command) {
        return new ProcessSpec(List.of(command), workingDirectory, timeout, Map.of(), Integer.MAX_VALUE);
    }
}
