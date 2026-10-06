package com.shellmind.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationContextVO {
    private LinkedList<IntentHistoryEntryVO> recentIntents;
    private int turnCount;
    private long sessionStartTime;
    private IntentTypeEnumVO lastIntent;

    /**
     * Recent-entity tracking (used for coreference resolution).
     * key: entity type (service, file, command, etc.)
     * value: entity value
     */
    @Builder.Default
    private Map<String, String> lastEntities = new HashMap<>();

    /**
     * Get the most recent entity value.
     * @param entityKey entity type
     * @return entity value, or null if missing
     */
    public String getLastEntity(String entityKey) {
        return lastEntities != null ? lastEntities.get(entityKey) : null;
    }

    /**
     * Record an entity.
     */
    public void putEntity(String key, String value) {
        if (lastEntities == null) lastEntities = new HashMap<>();
        if (key != null && value != null) {
            lastEntities.put(key, value);
        }
    }
}
