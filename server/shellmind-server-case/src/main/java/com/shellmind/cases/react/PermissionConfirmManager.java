package com.shellmind.cases.react;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission-confirm wait manager
 *
 * <p>Cross-layer coordination: ToolCallNode starts a confirm request → blocks →
 * PermissionResolveController (trigger layer) receives the user callback → wakes it
 *
 * @author Teaching Edition - ShellMind
 * 2026/6/22
 */
@Slf4j
@Component
public class PermissionConfirmManager {

    /** confirmId → wait lock */
    private final Map<String, Object> waitLocks = new ConcurrentHashMap<>();

    /** confirmId → confirm result */
    private final Map<String, PermissionResolveResult> results = new ConcurrentHashMap<>();

    /**
     * Block waiting for the user confirmation (with timeout)
     *
     * @param confirmId confirm-request ID
     * @param timeoutMs timeout
     * @return user confirmation result, null = timeout
     */
    public PermissionResolveResult awaitConfirmation(String confirmId, long timeoutMs) {
        Object lock = new Object();
        waitLocks.put(confirmId, lock);

        synchronized (lock) {
            try {
                // Check first whether a result already exists (the user may have replied before the wait)
                PermissionResolveResult existing = results.get(confirmId);
                if (existing != null) {
                    return existing;
                }

                lock.wait(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for permission confirmation: confirmId={}", confirmId);
            }
        }

        PermissionResolveResult result = results.get(confirmId);
        // Cleanup
        waitLocks.remove(confirmId);
        results.remove(confirmId);
        return result;
    }

    /**
     * User confirmation callback (called by the controller)
     */
    public void resolve(String confirmId, boolean approved, String modifiedArgs) {
        PermissionResolveResult result = new PermissionResolveResult(confirmId, approved, modifiedArgs, System.currentTimeMillis());
        results.put(confirmId, result);

        Object lock = waitLocks.get(confirmId);
        if (lock != null) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /**
     * Confirmation-result DTO
     */
    public static class PermissionResolveResult {
        private final String confirmId;
        private final boolean approved;
        private final String modifiedArgs;
        private final long resolvedAt;

        public PermissionResolveResult(String confirmId, boolean approved, String modifiedArgs, long resolvedAt) {
            this.confirmId = confirmId;
            this.approved = approved;
            this.modifiedArgs = modifiedArgs;
            this.resolvedAt = resolvedAt;
        }

        public String getConfirmId() { return confirmId; }
        public boolean isApproved() { return approved; }
        public String getModifiedArgs() { return modifiedArgs; }
        public long getResolvedAt() { return resolvedAt; }
    }
}
