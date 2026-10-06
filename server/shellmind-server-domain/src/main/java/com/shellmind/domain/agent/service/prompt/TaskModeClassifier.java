package com.shellmind.domain.agent.service.prompt;

import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class TaskModeClassifier {

    // Patterns are bilingual: the Chinese alternatives come first, English ones are case-insensitive.
    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "了吗|好了吗|做了吗|完成了吗|是否已经|是否完成|是否完善|有没有(?:修改|更新|完善|完成)|" +
            "(?:还有什么|还有哪些)(?:问题|风险|不足|欠缺)|(?:欠缺|缺少|还差)什么|存在什么问题|" +
            "(?i)(?:is it|are they|has it|have you).*(?:done|finished|complete|ready|improved)|" +
            "(?:did you|have you).*(?:finish|complete|update|improve)|" +
            "(?:is|are) (?:it|this|that) (?:done|finished|complete|ready)|" +
            "whether (?:already |it )?(?:complete|done|finished|improved)|" +
            "(?:any remaining|what's still|what(?:'s| is) (?:still )?(?:missing|lacking|left))|" +
            "(?:what(?:'s| is) (?:still )?(?:missing|lacking|needed)|what's wrong|what issues remain|still missing)|" +
            "(?:complete|done|finished|improved) yet"
    );

    private static final Pattern CONTINUATION_PATTERN = Pattern.compile(
            "^(?:开始|继续|按(?:建议|清单|上述|上面|这些)|逐项|直接|立即|动手|执行吧|处理吧|完善吧|修复吧|干起来|落实)|" +
            "(?i)^(?:start|continue|follow (?:the )?(?:suggestion|list|above|these)|item by item|directly|immediately|go ahead|just (?:do|execute|handle|improve|fix)|get started|implement)"
    );

    private static final Pattern TASK_VERB_PATTERN = Pattern.compile(
            "处理|完善|修复|修改|实现|编写|重构|补充|优化|更新|部署|迁移|配置|新增|删除|" +
            "(?i)\\b(?:handl|process|improv|refin|fix|repair|modif|chang|implement|writ|refactor|supplement|" +
            "optimi[sz]|updat|deploy|migrat|configur|add|creat|delet)\\w*"
    );

    private static final Pattern EXPLICIT_TARGET_PATTERN = Pattern.compile(
            "(?i)(/[\\w.\\-/]+)|\\.(?:java|kt|py|go|js|ts|tsx|jsx|md|yml|yaml|xml|json|sql|sh)|" +
            "README|文件|方法|接口|函数|类|模块|这些|上面的|建议|清单|" +
            "\\b(?:file|method|api|interface|function|class|module|these|above|suggestion|list)\\b"
    );

    private static final Pattern AMBIGUOUS_TASK_PATTERN = Pattern.compile(
            "^(?:帮我|请|麻烦)?\\s*(?:部署|搭建|迁移|修复|修改|完善|优化|重构|配置)(?:一下|它|这个)?[。.!！？?]?$|" +
            "(?i)^(?:please |help me |could you )?\\s*(?:deploy|set up|setup|migrate|fix|modify|improve|optimize|refactor|configure)(?: it| this)?[.!?]?$"
    );

    public TaskModeVO classify(String userMessage, String priorUserInstructions) {
        String message = normalize(userMessage);
        if (message.isEmpty()) {
            return TaskModeVO.CONVERSATIONAL;
        }

        if (STATUS_PATTERN.matcher(message).find()) {
            return TaskModeVO.STATUS;
        }

        boolean hasPriorContext = !normalize(priorUserInstructions).isEmpty();
        if (hasPriorContext && CONTINUATION_PATTERN.matcher(message).find()) {
            return TaskModeVO.EXECUTE;
        }

        if (TASK_VERB_PATTERN.matcher(message).find()
                && (EXPLICIT_TARGET_PATTERN.matcher(message).find() || hasPriorContext)) {
            return TaskModeVO.EXECUTE;
        }

        if (AMBIGUOUS_TASK_PATTERN.matcher(message).matches()
                || TASK_VERB_PATTERN.matcher(message).find()) {
            return TaskModeVO.CLARIFY;
        }

        return TaskModeVO.CONVERSATIONAL;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
