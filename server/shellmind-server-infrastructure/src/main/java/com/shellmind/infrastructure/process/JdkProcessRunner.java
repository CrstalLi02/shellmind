package com.shellmind.infrastructure.process;

import com.shellmind.domain.coding.adapter.port.ProcessRunner;
import com.shellmind.domain.coding.model.valobj.ProcessOutcome;
import com.shellmind.domain.coding.model.valobj.ProcessSpec;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessRunner} implementation backed by {@link ProcessBuilder}.
 * <p>
 * Output is drained on a background thread so the child does not block on a full pipe;
 * only the first maxOutputLines are kept.
 */
@Component
public class JdkProcessRunner implements ProcessRunner {

    /** How long to wait for the output-drain thread after the process exits */
    private static final long DRAIN_JOIN_MS = 2000;

    @Override
    public ProcessOutcome run(ProcessSpec spec) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .redirectErrorStream(true);
        spec.environmentDefaults().forEach(builder.environment()::putIfAbsent);

        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (lines) {
                        if (lines.size() < spec.maxOutputLines()) {
                            lines.add(line);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closes when the process is killed
            }
        }, "process-output-drainer");
        drainer.setDaemon(true);
        drainer.start();

        boolean finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        drainer.join(DRAIN_JOIN_MS);
        synchronized (lines) {
            return new ProcessOutcome(!finished, finished ? process.exitValue() : -1, lines);
        }
    }
}
