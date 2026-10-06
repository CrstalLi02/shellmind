package com.shellmind.infrastructure.adapter.port;

import com.shellmind.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal session manager.
 * Infrastructure implementation for creating, reading, writing, and closing Shell channels.
 *
 * @author waissh dev
 */
@Slf4j
@Component
public class TerminalSessionPort implements ITerminalSessionPort {

    @Resource
    private SshSessionPort sshSessionService;

    /** sessionId -> Shell channel */
    private final Map<String, ChannelShell> channels = new ConcurrentHashMap<>();

    /** sessionId -> output stream */
    private final Map<String, OutputStream> outputStreams = new ConcurrentHashMap<>();

    /** sessionId -> input stream */
    private final Map<String, InputStream> inputStreams = new ConcurrentHashMap<>();

    /** sessionId -> unread output buffer */
    private final Map<String, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    /** sessionId -> agent-only buffer (unaffected by frontend polling) */
    private final Map<String, StringBuilder> agentBuffers = new ConcurrentHashMap<>();

    /** sessionId -> whether agent capture mode is on */
    private final Map<String, Boolean> agentCaptureMode = new ConcurrentHashMap<>();

    /** sessionId -> whether the reader thread is alive */
    private final Map<String, Boolean> readerAlive = new ConcurrentHashMap<>();

    /** connectionId -> currently active sessionId (one terminal session per connection) */
    private final Map<String, String> activeConnectionSession = new ConcurrentHashMap<>();

    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        // Only one terminal session per connectionId; close the old one first
        String oldSessionId = activeConnectionSession.get(connectionId);
        if (oldSessionId != null) {
            log.info("Closing previous terminal session to avoid duplicate connectionId={} oldSessionId={}", connectionId, oldSessionId);
            cleanup(oldSessionId);
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            Session session = sshSessionService.getSession(connectionId);
            if (session == null || !session.isConnected()) {
                throw new IllegalStateException("SSH session is unavailable connectionId=" + connectionId);
            }

            ChannelShell channel = (ChannelShell) session.openChannel("shell");
            channel.setPty(true);
            channel.setPtySize(cols, rows, 480, 640);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channel.connect(5000);

            channels.put(sessionId, channel);
            inputStreams.put(sessionId, in);
            outputStreams.put(sessionId, out);
            outputBuffers.put(sessionId, new StringBuilder());
            agentBuffers.put(sessionId, new StringBuilder());
            agentCaptureMode.put(sessionId, false);
            activeConnectionSession.put(connectionId, sessionId);

            // Start the output reader thread to keep draining shell output into the buffer
            startOutputReader(sessionId, in);

            // Wait for the first Shell output, then wait a bit more so MOTD can fully arrive
            // Then consume the buffer and return it as initialOutput to the frontend
            // The frontend no longer needs to poll for initial output, which avoids the intermittent blank-screen issue
            StringBuilder buffer = outputBuffers.get(sessionId);
            long waitDeadline = System.currentTimeMillis() + 3000; // wait up to 3s for first data
            try {
                // Phase 1: wait until first data arrives
                while (System.currentTimeMillis() < waitDeadline) {
                    synchronized (buffer) {
                        if (buffer.length() > 0) {
                            break;
                        }
                    }
                    Thread.sleep(30);
                }
                // Phase 2: wait an extra 200ms so MOTD/prompt can fully arrive
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            log.info("Terminal session opened sessionId={} connectionId={}", sessionId, connectionId);
            return sessionId;

        } catch (Exception e) {
            log.error("Failed to open terminal session connectionId={}", connectionId, e);
            cleanup(sessionId);
            throw new RuntimeException("Failed to open terminal: " + e.getMessage(), e);
        }
    }

    /** Write retry count */
    private static final int WRITE_MAX_RETRIES = 2;

    /** Write with retry on I/O failure */
    @Override
    public void write(String sessionId, String command) {
        OutputStream out = outputStreams.get(sessionId);
        if (out == null) {
            throw new IllegalArgumentException("Terminal session does not exist or is closed sessionId=" + sessionId);
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= WRITE_MAX_RETRIES; attempt++) {
            try {
                out.write(command.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return; // success
            } catch (IOException e) {
                lastError = e;
                log.warn("Failed to write to terminal (attempt={}/{}) sessionId={} reason={}",
                        attempt, WRITE_MAX_RETRIES, sessionId, e.getMessage());
                // Throw after the last retry
                if (attempt == WRITE_MAX_RETRIES) {
                    break;
                }
                // Brief wait before retry
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.error("Failed to write to terminal (retried {} times) sessionId={}", WRITE_MAX_RETRIES, sessionId, lastError);
        throw new RuntimeException("Failed to write to terminal: " + lastError.getMessage(), lastError);
    }

    @Override
    public String read(String sessionId) {
        StringBuilder buffer = outputBuffers.get(sessionId);
        if (buffer == null) {
            throw new IllegalArgumentException("Terminal session does not exist or is closed sessionId=" + sessionId);
        }

        Boolean alive = readerAlive.get(sessionId);
        if (alive != null && !alive) {
            ChannelShell channel = channels.get(sessionId);
            if (channel == null || !channel.isConnected()) {
                return "\u001b[31m\r\n[Connection lost]\u001b[0m\r\n";
            }
            InputStream in = inputStreams.get(sessionId);
            if (in != null) {
                log.info("Restarting terminal reader thread sessionId={}", sessionId);
                startOutputReader(sessionId, in);
            }
        }

        // Non-blocking: return whatever is currently in the buffer
        // Frontend polling is the wait mechanism; the backend should not wait again
        synchronized (buffer) {
            if (buffer.length() == 0) {
                return "";
            }
            String output = buffer.toString();
            buffer.setLength(0);
            return output;
        }
    }

    @Override
    public void setAgentCapture(String sessionId, boolean capture) {
        StringBuilder agentBuffer = agentBuffers.get(sessionId);
        if (agentBuffer != null) {
            synchronized (agentBuffer) {
                // Clear stale content before enabling capture
                if (capture) {
                    agentBuffer.setLength(0);
                }
            }
        }
        agentCaptureMode.put(sessionId, capture);
        log.debug("Agent capture mode: sessionId={}, capture={}", sessionId, capture);
    }

    @Override
    public String readAgentBuffer(String sessionId) {
        StringBuilder agentBuffer = agentBuffers.get(sessionId);
        if (agentBuffer == null) {
            return "";
        }
        synchronized (agentBuffer) {
            if (agentBuffer.length() == 0) {
                return "";
            }
            String output = agentBuffer.toString();
            agentBuffer.setLength(0);
            return output;
        }
    }

    @Override
    public void resize(String sessionId, int cols, int rows) {
        ChannelShell channel = channels.get(sessionId);
        if (channel == null || !channel.isConnected()) {
            throw new IllegalArgumentException("Terminal session does not exist or is closed sessionId=" + sessionId);
        }

        try {
            channel.setPtySize(cols, rows, 480, 640);
            log.debug("Terminal resized sessionId={} {}x{}", sessionId, cols, rows);
        } catch (Exception e) {
            log.error("Failed to resize terminal sessionId={}", sessionId, e);
            throw new RuntimeException("Failed to resize terminal: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeSession(String sessionId) {
        log.info("Closing terminal session sessionId={}", sessionId);
        cleanup(sessionId);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        ChannelShell channel = channels.get(sessionId);
        return channel != null && channel.isConnected();
    }

    // ========== Internals ==========

    /**
     * Start the output reader thread.
     * Keep looping on SocketTimeoutException (not a real disconnect);
     * exit only on EOF (-1) or a real IOException.
     */
    private void startOutputReader(String sessionId, InputStream in) {
        readerAlive.put(sessionId, true);
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            int consecutiveErrors = 0;
            
            try {
                int len;
                while (true) {
                    try {
                        if ((len = in.read(buf)) == -1) {
                            // in.read() returning -1 means the shell channel hit EOF
                            log.warn("Terminal Shell Channel EOF sessionId={}", sessionId);
                            break;
                        }
                        
                        consecutiveErrors = 0; // reset error count
                        
                        String text = new String(buf, 0, len, StandardCharsets.UTF_8);
                        StringBuilder buffer = outputBuffers.get(sessionId);
                        if (buffer != null) {
                            synchronized (buffer) {
                                buffer.append(text);
                            }
                        }
                        // If agent capture is on, also write to the agent-only buffer
                        StringBuilder agentBuffer = agentBuffers.get(sessionId);
                        Boolean capture = agentCaptureMode.get(sessionId);
                        if (agentBuffer != null && Boolean.TRUE.equals(capture)) {
                            synchronized (agentBuffer) {
                                agentBuffer.append(text);
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // SocketTimeout is not a disconnect; keep reading
                        log.debug("Terminal read timed out (not a disconnect); continuing sessionId={}", sessionId);
                    } catch (IOException e) {
                        consecutiveErrors++;
                        
                        ChannelShell ch = channels.get(sessionId);
                        // Channel still alive: likely a transient I/O issue, try to recover
                        if (ch != null && ch.isConnected() && consecutiveErrors < 3) {
                            log.warn("Terminal read I/O error ({}/3), retrying sessionId={} reason={}", 
                                    consecutiveErrors, sessionId, e.getMessage());
                            Thread.sleep(100); // brief wait before retry
                            continue;
                        }
                        
                        // Retry budget exhausted or channel already disconnected
                        log.warn("Terminal output read error sessionId={} reason={} consecutiveErrors={}", 
                                sessionId, e.getMessage(), consecutiveErrors);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.info("Terminal reader thread interrupted sessionId={}", sessionId);
                Thread.currentThread().interrupt();
            } finally {
                readerAlive.put(sessionId, false);

                // Diagnose why the thread exited
                ChannelShell ch = channels.get(sessionId);
                boolean channelConnected = ch != null && ch.isConnected();
                boolean channelClosed = ch != null && ch.isClosed();
                log.warn("Terminal output reader thread exited sessionId={} channelConnected={} channelClosed={}",
                        sessionId, channelConnected, channelClosed);

                // Notify a waiting read() that the thread has exited and no more data will arrive
                StringBuilder buffer = outputBuffers.get(sessionId);
                if (buffer != null) {
                    synchronized (buffer) {
                        buffer.notifyAll();
                    }
                }
            }
        }, "terminal-reader-" + sessionId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Release resources.
     */
    private void cleanup(String sessionId) {
        // Clear the connectionId -> sessionId mapping
        activeConnectionSession.entrySet().removeIf(e -> sessionId.equals(e.getValue()));

        try {
            OutputStream out = outputStreams.remove(sessionId);
            if (out != null) out.close();
        } catch (IOException ignored) {}

        try {
            InputStream in = inputStreams.remove(sessionId);
            if (in != null) in.close();
        } catch (IOException ignored) {}

        ChannelShell channel = channels.remove(sessionId);
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }

        outputBuffers.remove(sessionId);
        agentBuffers.remove(sessionId);
        agentCaptureMode.remove(sessionId);
        readerAlive.remove(sessionId);
    }

}
