package com.shellmind.infrastructure.process;

import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkProcessRunnerTest {

    private final JdkProcessRunner runner = new JdkProcessRunner();

    @TempDir
    Path workDir;

    @Test
    void capturesOutputExitCodeAndEnvironmentDefaults() throws Exception {
        ProcessOutcome outcome = runner.run(new ProcessSpec(
                List.of("sh", "-c", "echo out; echo err 1>&2; echo $SM_TEST_VAR; exit 3"),
                workDir, Duration.ofSeconds(10), Map.of("SM_TEST_VAR", "from-default"), 100));

        assertFalse(outcome.timedOut());
        assertEquals(3, outcome.exitCode());
        assertFalse(outcome.succeeded());
        assertEquals(List.of("out", "err", "from-default"), outcome.outputLines());
    }

    @Test
    void runsInWorkingDirectory() throws Exception {
        ProcessOutcome outcome = runner.run(ProcessSpec.of(workDir, Duration.ofSeconds(10), "pwd"));

        assertTrue(outcome.succeeded());
        assertEquals(workDir.toRealPath().toString(), Path.of(outcome.output().trim()).toRealPath().toString());
    }

    @Test
    void killsProcessOnTimeout() throws Exception {
        long start = System.currentTimeMillis();
        ProcessOutcome outcome = runner.run(ProcessSpec.of(workDir, Duration.ofMillis(300), "sleep", "30"));

        assertTrue(outcome.timedOut());
        assertFalse(outcome.succeeded());
        assertTrue(System.currentTimeMillis() - start < 10_000, "Should return promptly after timeout");
    }

    @Test
    void keepsDrainingBeyondMaxOutputLines() throws Exception {
        // Output far exceeds the pipe buffer; keep only the first 5 lines, but the process must finish instead of stalling on a full pipe
        ProcessOutcome outcome = runner.run(new ProcessSpec(
                List.of("sh", "-c", "i=0; while [ $i -lt 20000 ]; do echo line-$i-xxxxxxxxxxxxxxxxxxxxxxxxxxxx; i=$((i+1)); done"),
                workDir, Duration.ofSeconds(30), Map.of(), 5));

        assertFalse(outcome.timedOut());
        assertEquals(0, outcome.exitCode());
        assertEquals(5, outcome.outputLines().size());
        assertTrue(outcome.outputLines().get(0).startsWith("line-0-"));
    }
}
