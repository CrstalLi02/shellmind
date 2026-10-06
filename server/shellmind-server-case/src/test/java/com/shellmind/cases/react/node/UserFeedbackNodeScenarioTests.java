package com.shellmind.cases.react.node;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFeedbackNodeScenarioTests {

    private final UserFeedbackNode node = new UserFeedbackNode();

    @Test
    void repeatedFinalAnswerTailIsRemovedOnce() throws Exception {
        String answer = """
                Done. Added QuickSort.java under the project directory and verified it by running it.

                **Implementation notes:**

                - Quicksort core: divide and conquer, pick a pivot, recurse on both sides
                - Performance: median-of-three to avoid degeneration on sorted arrays

                ```text
                [Random array] before: [5,2,9] after: [2,5,9]
                [Empty array] before: [] after: []
                ```

                📋 Change summary

                - New files: /Users/demo/ai-mcp-gateway/QuickSort.java
                - Commands: java QuickSort.java
                """;
        String duplicated = "Let me inspect the project structure first.\n\n" + answer + "\n\n" + answer;

        String cleaned = invokeRemoveRepeatedAnswer(duplicated);
        assertEquals(1, count(cleaned, "Implementation notes"));
        assertEquals(1, count(cleaned, "Change summary"));
        assertTrue(cleaned.contains("Let me inspect the project structure first."));
    }

    @Test
    void shortRepeatedLinesAreNotMistakenForAnswerDuplication() throws Exception {
        String text = """
                Before: [1]
                after: [1]

                Before: [1]
                after: [1]
                """;

        assertEquals(text, invokeRemoveRepeatedAnswer(text));
    }

    @Test
    void emptyResultAlwaysHasExplicitStopReason() throws Exception {
        assertEquals("Automatically stopped: the same tool sequence ran for several rounds. Add more information or adjust the task before continuing.",
                invokeEmptyResultNotice("diminishing_returns"));
        assertEquals("Automatically stopped: idle timeout.", invokeEmptyResultNotice("idle_timeout"));
        assertEquals("Stopped: maximum tool-call count reached.", invokeEmptyResultNotice("max_tool_calls"));
    }

    private String invokeRemoveRepeatedAnswer(String text) throws Exception {
        Method method = UserFeedbackNode.class.getDeclaredMethod("removeRepeatedAnswer", String.class);
        method.setAccessible(true);
        return (String) method.invoke(node, text);
    }

    private String invokeEmptyResultNotice(String stopReason) throws Exception {
        Method method = UserFeedbackNode.class.getDeclaredMethod("buildEmptyResultNotice", String.class);
        method.setAccessible(true);
        return (String) method.invoke(node, stopReason);
    }

    private int count(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
