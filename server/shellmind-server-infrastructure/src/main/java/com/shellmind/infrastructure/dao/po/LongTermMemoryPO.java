package com.shellmind.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Long-term memory persistence object (PO).
 * <p>
 * Maps to the {@code long_term_memory} table and stores structured memories extracted from the conversation stream.
 * <p>
 * Unique key (userId, memoryType, memoryKey) prevents duplicate inserts;
 * a repeat extract performs UPDATE and hit_count+1.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryPO {

    /** Auto-increment primary key */
    private Long id;

    /** User ID */
    private String userId;

    /** Source session ID */
    private String sessionId;

    /** Memory type: USER_PREFERENCE / ENVIRONMENT_FACT / SOFTWARE_FACT / TROUBLESHOOTING_CASE */
    private String memoryType;

    /** Dedup key (generation strategy varies by type) */
    private String memoryKey;

    /** Memory content */
    private String content;

    /** Keyword set (comma-separated, used for recall matching) */
    private String keywords;

    /** Source role: user / tool / assistant */
    private String sourceRole;

    /** Confidence (0.0–1.0) */
    private Double confidence;

    /** Hit / update count */
    private Integer hitCount;

    /** Created at */
    private LocalDateTime createdAt;

    /** Updated at */
    private LocalDateTime updatedAt;
}
