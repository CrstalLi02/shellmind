package com.shellmind.domain.memory.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Long-term memory entity (domain layer).
 * <p>
 * Maps to the {@code long_term_memory} table and stores structured memories extracted from the conversation stream.
 * Written automatically by {@code LongTermMemoryService} at each ReAct-loop stage.
 * <p>
 * Four memory types ({@code memoryType}):
 * <ul>
 *   <li>{@code USER_PREFERENCE} - user preference (hints such as "from now on", "default", "remember"), key=sha1("pref:"+content)</li>
 *   <li>{@code ENVIRONMENT_FACT} - environment fact (OS detection), key="os:ubuntu"</li>
 *   <li>{@code SOFTWARE_FACT} - software version (regex match), key="redis:version"</li>
 *   <li>{@code TROUBLESHOOTING_CASE} - troubleshooting case (failure signal / assistant conclusion), key=sha1("failure:"/"assistant:"+content)</li>
 * </ul>
 * <p>
 * Unique key (userId, memoryType, memoryKey) prevents duplicate rows; repeated extraction increments hit_count.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryEntity {

    /** Auto-increment primary key. */
    private Long id;

    /** User ID. */
    private String userId;

    /** Source session ID. */
    private String sessionId;

    /** Memory type: USER_PREFERENCE / ENVIRONMENT_FACT / SOFTWARE_FACT / TROUBLESHOOTING_CASE. */
    private String memoryType;

    /** Dedup key (generation strategy differs by type; keeps writes idempotent). */
    private String memoryKey;

    /** Memory content (truncated, max 300~500 characters). */
    private String content;

    /** Keyword set (comma-separated, used for overlap matching during recall). */
    private String keywords;

    /** Source role: user / tool / assistant. */
    private String sourceRole;

    /** Confidence (0.0~1.0; USER_PREFERENCE=0.85 is highest). */
    private Double confidence;

    /** Hit/update count (incremented when the same memory is extracted again). */
    private Integer hitCount;

    /** Created at. */
    private Date createdAt;

    /** Updated at. */
    private Date updatedAt;
}
