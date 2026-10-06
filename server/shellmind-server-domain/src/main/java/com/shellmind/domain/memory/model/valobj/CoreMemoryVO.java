package com.shellmind.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Core memory value object.
 * <p>
 * Stores long-term user preferences, rules, corrections, and key decisions.
 * Unlike MilestoneVO (event log), CoreMemoryVO is refined long-term knowledge.
 *
 * @author ShellMind
 * 2026/6/26
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CoreMemoryVO {

    /** Memory ID. */
    private Long id;

    /** User ID. */
    private String userId;

    /** Memory scope: user (global) / session (session-level). */
    private String scope;

    /** Memory category: Rule / Preference / Decision / Correction / Fact. */
    private String category;

    /** Memory title (short description). */
    private String title;

    /** Keyword list (comma-separated, used for relevance matching). */
    private String keywords;

    /** Memory body. */
    private String content;

    /** Priority: 1-5, higher is more important. */
    private Integer priority;

    /** Created at. */
    private Long createdAt;

    /** Last used at (used to evict cold memories). */
    private Long lastUsedAt;

    /** Use count (used to evict cold memories). */
    private Integer useCount;

    /** Source session ID. */
    private String sourceSessionId;
}
