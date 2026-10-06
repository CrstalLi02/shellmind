package com.shellmind.domain.agent.service.prompt;

import com.shellmind.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import com.shellmind.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskModeTests {

    private final TaskModeClassifier classifier = new TaskModeClassifier();
    private final DynamicPromptBuilder promptBuilder = new DynamicPromptBuilder();

    @Test
    void classifiesStatusQuestionAsReadOnlyReport() {
        assertEquals(TaskModeVO.STATUS, classifier.classify("Have you finished the README", "The previous round modified the code."));
        assertEquals(TaskModeVO.STATUS, classifier.classify("What is still missing from this API implementation", "The API analysis is as follows."));
    }

    @Test
    void classifiesContinuationAsExecute() {
        assertEquals(TaskModeVO.EXECUTE, classifier.classify("start improving", "The missing items were listed above."));
        assertEquals(TaskModeVO.EXECUTE, classifier.classify("follow the suggestions", "The fix plan was listed above."));
    }

    @Test
    void classifiesAmbiguousTaskAsClarify() {
        assertEquals(TaskModeVO.CLARIFY, classifier.classify("help me deploy", null));
    }

    @Test
    void injectsDeterministicModeIntoPrompt() {
        PromptContextVO context = PromptContextVO.builder().taskMode(TaskModeVO.STATUS).build();
        String prompt = promptBuilder.buildMessagePrefix(context);

        assertTrue(prompt.contains("[Current-turn task mode]"));
        assertTrue(prompt.contains("do not write"));
    }
}
