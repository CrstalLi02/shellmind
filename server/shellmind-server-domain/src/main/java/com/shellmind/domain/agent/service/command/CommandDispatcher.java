package com.shellmind.domain.agent.service.command;

import com.shellmind.domain.agent.model.valobj.command.CommandRequest;
import com.shellmind.domain.agent.model.valobj.command.CommandResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.shellmind.domain.agent.adapter.port.ClientChannel;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local command dispatcher.
 *
 * <p>Responsibilities:
 * - Send local execution commands to the Client over SSE
 * - Wait for the Client to post execution results back over HTTP
 * - Manage command lifecycle (timeout, disconnect, cancel)
 *
 * <p>Architecture:
 * Server (brain) ──SSE──► Client (hands)
 *                  ◄──HTTP POST──
 *
 * @author shellmind dev
 */
@Slf4j
@Service
public class CommandDispatcher {

    @Resource
    private ObjectMapper objectMapper;

    /** Run registry: look up the client channel by the command's session */
    @Resource
    private AgentRunRegistry runRegistry;

    // ═══════════════════════════════════════════════════════════════
    //  Pending-command management
    // ═══════════════════════════════════════════════════════════════

    /** cmdId → pending Future */
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2: result cache + idempotent execution
    // ═══════════════════════════════════════════════════════════════

    /** cmdId → cached result (replayed by GET /tool_result/pending, TTL 5min) */
    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private static final long RESULT_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Submitted cmdId set (idempotent dedupe, TTL 5min) */
    private final ConcurrentHashMap<String, Long> submittedCmdIds = new ConcurrentHashMap<>();
    private static final long IDEMPOTENT_TTL_MS = 5 * 60 * 1000L;

    /**
     * Cache a result (called when the Client posts a result; also writes the cache).
     */
    private void cacheResult(String cmdId, CommandResult result) {
        resultCache.put(cmdId, new CachedResult(result, System.currentTimeMillis()));
        // Evict expired cache entries
        long now = System.currentTimeMillis();
        resultCache.entrySet().removeIf(e -> now - e.getValue().cachedAt > RESULT_CACHE_TTL_MS);
    }

    /**
     * Check whether a cmdId was already submitted (idempotent dedupe).
     * @return true = duplicate, should reject
     */
    public boolean isDuplicate(String cmdId) {
        Long existing = submittedCmdIds.putIfAbsent(cmdId, System.currentTimeMillis());
        if (existing != null) {
            return true;
        }
        // Evict expired cmdIds
        long now = System.currentTimeMillis();
        submittedCmdIds.entrySet().removeIf(e -> now - e.getValue() > IDEMPOTENT_TTL_MS);
        return false;
    }

    /**
     * Get and clear a cached result (called by GET /tool_result/pending).
     * Invoked when the Client polls; the result is removed from the cache after retrieval.
     *
     * @param cmdId command ID
     * @return cached result, or null if missing or expired
     */
    public CommandResult pollResult(String cmdId) {
        CachedResult cached = resultCache.remove(cmdId);
        if (cached == null) return null;
        // Check TTL
        if (System.currentTimeMillis() - cached.cachedAt > RESULT_CACHE_TTL_MS) {
            return null;
        }
        return cached.result;
    }

    /**
     * Get all cached results pending poll (for batch checks after Client reconnect).
     */
    public Map<String, CommandResult> pollAllResults() {
        Map<String, CommandResult> results = new HashMap<>();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedResult>> it = resultCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedResult> entry = it.next();
            if (now - entry.getValue().cachedAt <= RESULT_CACHE_TTL_MS) {
                results.put(entry.getKey(), entry.getValue().result);
            }
            it.remove();
        }
        return results;
    }

    /** Internal cached-result holder */
    private static class CachedResult {
        final CommandResult result;
        final long cachedAt;
        CachedResult(CommandResult result, long cachedAt) {
            this.result = result;
            this.cachedAt = cachedAt;
        }
    }

    /**
     * Dispatch a command and block until the result arrives.
     *
     * @param request   command request
     * @param timeoutMs timeout in milliseconds
     * @return execution result
     */
    public CommandResult dispatchAndWait(CommandRequest request, long timeoutMs) {
        String cmdId = request.getCmdId();
        log.info("[CommandDispatcher] Dispatching command: cmdId={}, type={}, command={}, timeoutMs={}",
                cmdId, request.getType(), request.getCommand(), timeoutMs);

        // 1. Check whether an emitter is available
        ClientChannel emitter = runRegistry.channel(request.getSessionId()).orElse(null);
        if (emitter == null) {
            log.warn("[CommandDispatcher] Session has no client channel, cannot dispatch command: cmdId={}, session={}", cmdId, request.getSessionId());
            return CommandResult.error(cmdId, request.getSessionId(),
                    "Local execution environment is unavailable (SSE connection not established)", 0);
        }

        // 2. Create the Future
        PendingCommand pending = new PendingCommand(request);
        pendingCommands.put(cmdId, pending);

        try {
            // 3. Push the command over SSE
            sendCommandViaSse(emitter, request);

            // 4. Block waiting for the result
            CommandResult result = pending.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("[CommandDispatcher] Command completed: cmdId={}, status={}, exitCode={}, durationMs={}",
                    cmdId, result.getStatus(), result.getExitCode(), result.getDurationMs());
            return result;

        } catch (TimeoutException e) {
            log.warn("[CommandDispatcher] Command timed out: cmdId={}, timeoutMs={}", cmdId, timeoutMs);
            return CommandResult.timeout(cmdId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CommandDispatcher] Command interrupted: cmdId={}", cmdId);
            return CommandResult.error(cmdId, request.getSessionId(), "Command interrupted", 0);
        } catch (Exception e) {
            log.error("[CommandDispatcher] Command dispatch exception: cmdId={}", cmdId, e);
            return CommandResult.error(cmdId, request.getSessionId(), "Command dispatch exception: " + e.getMessage(), 0);
        } finally {
            // 5. Cleanup
            pendingCommands.remove(cmdId);
        }
    }

    /**
     * Called when the Client posts a result.
     *
     * @param cmdId  command ID
     * @param result execution result
     */
    public void completeCommand(String cmdId, CommandResult result) {
        // 1. Write the cache (for GET poll replay)
        cacheResult(cmdId, result);

        // 2. Wake the blocking Future
        PendingCommand pending = pendingCommands.get(cmdId);
        if (pending != null) {
            log.info("[CommandDispatcher] Received command result: cmdId={}, status={}", cmdId, result.getStatus());
            pending.getFuture().complete(result);
        } else {
            log.warn("[CommandDispatcher] Received unknown command result: cmdId={} (may have timed out or been cleaned up; result cached for GET replay)", cmdId);
        }
    }

    /**
     * Current number of pending commands.
     */
    public int getPendingCount() {
        return pendingCommands.size();
    }

    /**
     * Mark all pending commands as disconnected (called when the Client disconnects).
     */
    public void markAllDisconnected() {
        log.warn("[CommandDispatcher] Marking all pending commands as disconnected, count: {}", pendingCommands.size());
        for (Map.Entry<String, PendingCommand> entry : pendingCommands.entrySet()) {
            PendingCommand pending = pendingCommands.remove(entry.getKey());
            if (pending != null) {
                pending.getFuture().complete(
                        CommandResult.disconnected(entry.getKey()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Push a command to the Client over SSE.
     */
    private void sendCommandViaSse(ClientChannel emitter, CommandRequest request) throws IOException {
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("event", request.getType());
        event.put("cmdId", request.getCmdId());
        event.put("command", request.getCommand());
        if (request.getCwd() != null) event.put("cwd", request.getCwd());
        event.put("timeoutMs", request.getTimeoutMs());
        event.put("sessionId", request.getSessionId());
        event.put("timestamp", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(event);
        emitter.send(json + "\n");

        log.debug("[CommandDispatcher] Command pushed over SSE: cmdId={}", request.getCmdId());
    }

    /**
     * Send a keepalive heartbeat to the session's client (called from the AiCallNode event loop).
     *
     * @return false if send failed (client already disconnected); a session with no channel is treated as success
     */
    public boolean sendHeartbeat(String sessionId) {
        ClientChannel emitter = runRegistry.channel(sessionId).orElse(null);
        if (emitter == null) return true;
        try {
            String heartbeat = "{\"event\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}\n";
            emitter.send(heartbeat);
            return true;
        } catch (IOException e) {
            log.debug("[CommandDispatcher] Heartbeat send failed: {}", e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Inner classes
    // ═══════════════════════════════════════════════════════════════

    /**
     * A pending command.
     */
    private static class PendingCommand {
        private final CommandRequest request;
        private final java.util.concurrent.CompletableFuture<CommandResult> future;

        public PendingCommand(CommandRequest request) {
            this.request = request;
            this.future = new java.util.concurrent.CompletableFuture<>();
        }

        public CommandRequest getRequest() {
            return request;
        }

        public java.util.concurrent.CompletableFuture<CommandResult> getFuture() {
            return future;
        }
    }
}
