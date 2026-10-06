package com.shellmind.domain.agent.service;

import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import com.shellmind.domain.agent.service.execution.ExecutionPolicyResolver;
import com.shellmind.domain.agent.service.intent.RuleIntentClassifier;
import com.shellmind.domain.agent.service.prompt.TaskModeClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyword-based classification must recognize both Chinese and English user input.
 * Guards against translating keyword lists instead of extending them.
 */
class BilingualKeywordMatchingTests {

    private final ExecutionPolicyResolver resolver = new ExecutionPolicyResolver();
    private final RuleIntentClassifier ruleClassifier = new RuleIntentClassifier();
    private final TaskModeClassifier taskModeClassifier = new TaskModeClassifier();

    @ParameterizedTest
    @ValueSource(strings = {"开始处理", "完善这些内容。", "帮我修复一下这个报错", "start handling it", "please fix this error", "go ahead"})
    void executionVerbsEscalateMisclassifiedChatToExecutable(String message) {
        IntentResultVO chat = IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.6).build();
        assertFalse(resolver.resolve(chat, message, null).readOnly(), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好呀，今天天气不错", "the preprocessing step looks fine"})
    void ordinaryChatStaysReadOnly(String message) {
        IntentResultVO chat = IntentResultVO.builder().intent(IntentTypeEnumVO.EXPLAIN).confidence(0.6).build();
        // "preprocessing" must not count as the verb "process" (word boundary)
        assertTrue(resolver.resolve(chat, message, null).readOnly(), message);
    }

    @ParameterizedTest
    @CsvSource({
            "nginx 挂了，502 报错, DIAGNOSE",
            "nginx is down with 502 errors, DIAGNOSE",
            "帮我备份一下数据库, BACKUP",
            "backup the database, BACKUP",
            "这是什么项目, EXPLAIN",
            "what is this project, EXPLAIN"
    })
    void ruleClassifierRecognizesBothLanguages(String message, IntentTypeEnumVO expected) {
        assertEquals(expected, ruleClassifier.classify(message, null).getIntent(), message);
    }

    @ParameterizedTest
    @CsvSource({
            "修复 README 里的错别字, EXECUTE",
            "fix the typo in README, EXECUTE",
            "完成了吗, STATUS",
            "is it done yet, STATUS"
    })
    void taskModeRecognizesBothLanguages(String message, TaskModeVO expected) {
        assertEquals(expected, taskModeClassifier.classify(message, ""), message);
    }
}
