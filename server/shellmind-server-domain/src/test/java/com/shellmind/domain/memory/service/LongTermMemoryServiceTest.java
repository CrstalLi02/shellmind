package com.shellmind.domain.memory.service;

import com.shellmind.domain.memory.adapter.repository.ILongTermMemoryRepository;
import com.shellmind.domain.memory.model.entity.LongTermMemoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-term memory service tests — in-memory repository double verifying four memory types, upsert dedup, and keyword recall.
 */
public class LongTermMemoryServiceTest {

    /** In-memory repository: dedup by (userId, type, key); repeat writes increment hitCount. */
    private static class InMemoryRepository implements ILongTermMemoryRepository {
        private final Map<String, LongTermMemoryEntity> store = new LinkedHashMap<>();

        @Override
        public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
            String key = memoryEntity.getUserId() + "|" + memoryEntity.getMemoryType() + "|" + memoryEntity.getMemoryKey();
            LongTermMemoryEntity exist = store.get(key);
            memoryEntity.setUpdatedAt(new Date());
            if (exist != null) {
                memoryEntity.setHitCount(exist.getHitCount() + 1);
            }
            store.put(key, memoryEntity);
        }

        @Override
        public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
            return store.values().stream()
                    .filter(memory -> userId.equals(memory.getUserId()))
                    .sorted(Comparator.comparing(LongTermMemoryEntity::getUpdatedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        List<LongTermMemoryEntity> all() {
            return new ArrayList<>(store.values());
        }
    }

    private InMemoryRepository repository;
    private LongTermMemoryService service;

    @BeforeEach
    public void setUp() {
        repository = new InMemoryRepository();
        service = new LongTermMemoryService();
        ReflectionTestUtils.setField(service, "longTermMemoryRepository", repository);
    }

    @Test
    public void shouldExtractUserPreferenceAndDeduplicate() {
        service.recordUserMessage("u1", "s1", "from now on always add sudo when running commands", null);
        service.recordUserMessage("u1", "s2", "from now on always add sudo when running commands", null);
        service.recordUserMessage("u1", "s1", "check the disk", null);

        List<LongTermMemoryEntity> memories = repository.all();
        assertEquals(1, memories.size());
        assertEquals("USER_PREFERENCE", memories.get(0).getMemoryType());
        assertEquals(2, memories.get(0).getHitCount().intValue());
    }

    @Test
    public void shouldExtractEnvironmentSoftwareAndFailureFromToolOutput() {
        service.recordToolObservation("u1", "s1", "executeCommand",
                "Ubuntu 22.04 LTS\nredis_version:7.0.11", true);
        service.recordToolObservation("u1", "s1", "executeCommand",
                "bash: nginx: Permission denied", false);

        List<String> types = repository.all().stream().map(LongTermMemoryEntity::getMemoryType).toList();
        assertTrue(types.contains("ENVIRONMENT_FACT"), types.toString());
        assertTrue(types.contains("SOFTWARE_FACT"), types.toString());
        assertTrue(types.contains("TROUBLESHOOTING_CASE"), types.toString());
    }

    @Test
    public void shouldOnlyKeepAssistantConclusions() {
        service.recordAssistantConclusion("u1", "s1", "Okay, let me take a look");
        service.recordAssistantConclusion("u1", "s1", "Conclusion: the root cause of 502 is upstream timeout; recommend increasing proxy_read_timeout");

        assertEquals(1, repository.all().size());
    }

    @Test
    public void shouldRecallRelevantMemoriesByKeyword() {
        service.recordToolObservation("u1", "s1", "executeCommand", "redis_version:7.0.11", true);
        service.recordUserMessage("u1", "s1", "from now on default to docker compose for deploys", null);
        service.recordToolObservation("u2", "s9", "executeCommand", "redis_version:6.2.0", true);

        String summary = service.buildMemorySummary("u1", "redis connection pool is full", 5);

        assertTrue(summary.contains("redis version is 7.0.11"), summary);
        assertTrue(!summary.contains("6.2.0"), "must not recall another user's memory: " + summary);
        // User preferences apply globally and are recalled even without keyword overlap
        assertTrue(summary.contains("USER_PREFERENCE"), summary);
    }

    @Test
    public void shouldIgnoreBlankInput() {
        service.recordUserMessage(null, "s1", "from now on always add sudo", null);
        service.recordToolObservation("u1", "s1", "executeCommand", "  ", false);

        assertEquals(0, repository.all().size());
        assertEquals("", service.buildMemorySummary("u1", "anything", 5));
    }
}
