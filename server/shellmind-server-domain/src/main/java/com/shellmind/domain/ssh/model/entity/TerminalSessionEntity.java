package com.shellmind.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Terminal session entity.
 * Aggregate root that manages the lifecycle of one SSH terminal session.
 *
 * @author waissh dev
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalSessionEntity {

    /** Session ID. */
    private String sessionId;

    /** SSH connection ID. */
    private String connectionId;

    /** Terminal columns. */
    private int cols;

    /** Terminal rows. */
    private int rows;

    /** Channel ID (JSch Channel ID). */
    private String channelId;

    /** Created at. */
    private LocalDateTime createdAt;

    /** Last active at. */
    private LocalDateTime lastActiveAt;

    /** Session status: 0-inactive, 1-active, 2-closed. */
    private int status;

    /**
     * Whether the session is currently valid.
     */
    public boolean isActive() {
        return status == 1 && sessionId != null && !sessionId.isBlank();
    }

    /**
     * Update last-active time.
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

}
