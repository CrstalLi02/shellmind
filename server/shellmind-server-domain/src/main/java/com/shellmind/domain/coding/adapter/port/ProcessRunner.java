package com.shellmind.domain.coding.adapter.port;

import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;

import java.io.IOException;

/**
 * Run an external program on the host that serves this process (git, build tools, etc.).
 * <p>
 * Unlike {@link com.shellmind.domain.agent.adapter.port.LocalCommandExecutor}, this starts the program
 * from an argument list with no shell parsing, for deterministic calls inside domain services.
 */
public interface ProcessRunner {

    /**
     * Run and wait until the process exits; on timeout the process (including children) is forcibly killed.
     *
     * @throws IOException if the program cannot be started
     */
    ProcessOutcome run(ProcessSpec spec) throws IOException, InterruptedException;
}
