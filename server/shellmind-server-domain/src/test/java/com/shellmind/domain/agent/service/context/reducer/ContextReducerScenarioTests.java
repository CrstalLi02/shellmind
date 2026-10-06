package com.shellmind.domain.agent.service.context.reducer;

import com.shellmind.domain.agent.service.engine.TokenEstimator;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextReducerScenarioTests {

    @Test
    void longConversationKeepsCriticalToolFailure() {
        PriorityReducer reducer = new PriorityReducer();
        List<Map<String, Object>> history = longConversationWithCriticalFailure(100, 20);

        List<Map<String, Object>> reduced = reducer.reduce(history, 800);

        assertTrue(reduced.size() > 2);
        assertTrue(containsContent(reduced, "CRITICAL_TOOL_ERROR"));
        assertTrue(containsContent(reduced, latestQuestion(history)));
        assertTrue(TokenEstimator.estimateMessages(reduced) <= 800);
    }

    @Test
    void hybridReducerKeepsRecentTurnsAndCriticalFailure() throws Exception {
        HybridReducer reducer = new HybridReducer();
        inject(reducer, "priorityReducer", new PriorityReducer());
        inject(reducer, "slidingReducer", new SlidingWindowReducer());

        List<Map<String, Object>> history = longConversationWithCriticalFailure(120, 30);
        List<Map<String, Object>> reduced = reducer.reduce(history, 900);

        assertTrue(reduced.size() > 2);
        assertTrue(containsContent(reduced, "CRITICAL_TOOL_ERROR"));
        assertTrue(containsContent(reduced, latestQuestion(history)));
        assertTrue(TokenEstimator.estimateMessages(reduced) <= 900);
    }

    @Test
    void veryLargeMessageStillKeepsMinimumRecentTurns() {
        PriorityReducer reducer = new PriorityReducer();
        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "old message ".repeat(1000)),
                Map.of("role", "assistant", "content", "old reply ".repeat(1000)),
                Map.of("role", "user", "content", "latest question"),
                Map.of("role", "assistant", "content", "latest answer")
        );

        List<Map<String, Object>> reduced = reducer.reduce(history, 10);

        assertTrue(containsContent(reduced, "latest question"));
        assertTrue(containsContent(reduced, "latest answer"));
        assertFalse(containsContent(reduced, "old message"));
    }

    private List<Map<String, Object>> longConversationWithCriticalFailure(int messageCount, int criticalIndex) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int index = 0; index < messageCount; index++) {
            String role = index % 2 == 0 ? "user" : "assistant";
            String content = ("message-" + index + "-").repeat(10);
            history.add(Map.of("role", role, "content", content));
        }
        history.set(criticalIndex, Map.of(
                "role", "tool",
                "content", "CRITICAL_TOOL_ERROR: compile failed at /src/main/java/UserService.java; exception=NullPointerException"
        ));
        history.add(Map.of("role", "user", "content", "Please continue fixing UserService based on the failure just now"));
        return history;
    }

    private boolean containsContent(List<Map<String, Object>> messages, String expected) {
        return messages.stream().anyMatch(message -> String.valueOf(message.get("content")).contains(expected));
    }

    private String latestQuestion(List<Map<String, Object>> history) {
        return String.valueOf(history.get(history.size() - 1).get("content"));
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
