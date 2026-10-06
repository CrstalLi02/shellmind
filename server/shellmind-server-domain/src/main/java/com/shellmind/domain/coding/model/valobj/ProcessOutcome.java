package com.shellmind.domain.coding.model.valobj;

import java.util.List;

/**
 * Result of running an external process.
 *
 * @param timedOut    whether the process was killed due to timeout (exitCode is then meaningless)
 * @param exitCode    exit code
 * @param outputLines merged output (at most maxOutputLines lines)
 */
public record ProcessOutcome(boolean timedOut, int exitCode, List<String> outputLines) {

    public ProcessOutcome {
        outputLines = List.copyOf(outputLines);
    }

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }

    public String output() {
        return String.join("\n", outputLines);
    }
}
