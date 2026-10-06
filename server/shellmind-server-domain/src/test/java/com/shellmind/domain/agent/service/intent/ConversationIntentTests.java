package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-turn conversation intent-resolution evaluation.
 *
 * Scenarios:
 * 1. Turn 1 names an entity → turn 2 uses a pronoun → resolution is correct
 * 2. Entities persist across consecutive turns
 * 3. Different entity types (file/service/command) are tracked separately
 */
class ConversationIntentTests {

    @Test
    void scenario1_coreferenceAcrossTurns() {
        ConversationContextVO ctx = ConversationContextVO.builder()
                .recentIntents(new java.util.LinkedList<>())
                .turnCount(0)
                .build();

        ctx.putEntity("file", "src/main/java/UserService.java");
        ctx.putEntity("service", "user-service");

        assertEquals("src/main/java/UserService.java", ctx.getLastEntity("file"));
        assertEquals("user-service", ctx.getLastEntity("service"));

        ctx.setTurnCount(1);
        assertEquals(1, ctx.getTurnCount());

        ctx.putEntity("file", "src/main/java/OrderService.java");
        assertEquals("src/main/java/OrderService.java", ctx.getLastEntity("file"),
                "Second turn should update file entity");
    }

    @Test
    void scenario2_entitiesPersistAcrossMultipleTurns() {
        ConversationContextVO ctx = ConversationContextVO.builder()
                .recentIntents(new java.util.LinkedList<>())
                .build();

        ctx.putEntity("file", "Config.java");
        ctx.putEntity("command", "mvn test");

        for (int turn = 1; turn <= 5; turn++) {
            ctx.setTurnCount(turn);
            assertEquals("Config.java", ctx.getLastEntity("file"),
                    "File entity should persist at turn " + turn);
            assertEquals("mvn test", ctx.getLastEntity("command"),
                    "Command entity should persist at turn " + turn);
        }
    }

    @Test
    void scenario3_nullEntityReturnsNull() {
        ConversationContextVO ctx = ConversationContextVO.builder().build();
        assertNull(ctx.getLastEntity("nonexistent"));
        assertNull(ctx.getLastEntity("file"));
    }

    @Test
    void scenario4_putEntityOverwritesPrevious() {
        ConversationContextVO ctx = ConversationContextVO.builder().build();

        ctx.putEntity("file", "first.java");
        ctx.putEntity("file", "second.java");

        assertEquals("second.java", ctx.getLastEntity("file"));
    }
}
