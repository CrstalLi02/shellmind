package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentRuleVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class RuleIntentClassifier implements IIntentClassifier {

    /** Keywords and patterns are bilingual: Chinese and English user input are both recognized. */
    private static final List<IntentRuleVO> RULES = List.of(
        rule(IntentTypeEnumVO.DIAGNOSE,
            List.of("挂了", "宕机", "满", "过高", "异常", "报错", "告警", "超时",
                    "down", "crashed", "outage", "502", "503", "504", "OOM", "full", "too high", "abnormal",
                    "error", "alert", "timeout", "crash", "panic", "fatal"),
            List.of("为什么.*(?:挂|报错|失败|不通)", "排查.*问题", "分析.*原因",
                    "(?i)why.*(?:down|error|fail|unreachable)", "(?i)troubleshoot.*(?:issue|problem)", "(?i)analy[sz]e.*(?:cause|reason)"),
            Map.of(List.of("修复", "解决", "fix", "resolve"), 0.1)),

        rule(IntentTypeEnumVO.CONFIGURE,
            List.of("配置", "修改配置", "参数", "调整", "设置", "调优",
                    "config", "configuration", "change config", "parameter", "tune", "setting", "optimize"),
            List.of("修改.*(?:conf|cfg|yml|properties|xml|json)", "设置.*参数",
                    "(?i)(?:change|edit|modify|update).*(?:conf|cfg|yml|properties|xml|json)", "(?i)set.*param"),
            Map.of()),

        rule(IntentTypeEnumVO.DEPLOY,
            List.of("部署", "发布", "回滚", "上线", "更新版本", "重启服务",
                    "deploy", "release", "rollback", "go live", "ship", "update version", "restart service"),
            List.of("(?:发布|部署).*版本", "回滚.*版本", "(?i)(?:release|deploy).*version", "(?i)rollback.*version"),
            Map.of()),

        rule(IntentTypeEnumVO.MONITOR,
            List.of("查看", "监控", "日志", "内存", "磁盘", "网络", "流量", "负载", "进程", "端口", "连接数",
                    "view", "check", "monitor", "log", "cpu", "memory", "disk", "network", "traffic",
                    "load", "process", "port", "connections"),
            List.of("(?:看|查|check).*(?:状态|情况|使用率)",
                    "(?i)(?:check|view|see|look).*(?:status|usage|situation)", "tail.*log"),
            Map.of()),

        rule(IntentTypeEnumVO.SECURITY,
            List.of("防火墙", "权限", "密钥", "证书", "安全", "漏洞",
                    "firewall", "iptables", "permission", "ssh", "key",
                    "certificate", "ssl", "tls", "security", "vulnerability", "CVE"),
            List.of("(?:开放|关闭).*端口", "配置.*(?:ssl|证书|密钥)",
                    "(?i)(?:open|close).*port", "(?i)(?:config|configure).*(?:ssl|certificate|key)"),
            Map.of()),

        rule(IntentTypeEnumVO.BACKUP,
            List.of("备份", "恢复", "导出", "迁移", "backup", "restore", "export", "import", "migrate"),
            List.of("备份.*(?:数据库|文件|配置)", "恢复.*数据", "(?i)backup.*(?:database|file|config)", "(?i)restore.*data"),
            Map.of()),

        rule(IntentTypeEnumVO.EXPLAIN,
            List.of("什么意思", "怎么理解", "解释", "说明",
                    "what does it mean", "how to understand", "explain", "describe", "what is", "how to"),
            List.of("这个命令.*(?:意思|作用|用途)", "这(?:是什么|个是什么)项目", "项目.*(?:是什么|结构|介绍)",
                    "介绍一下.*项目", "介绍.*项目", "了解.*项目",
                    "(?i)this command.*(?:mean|purpose|use)", "(?i)what is this project", "(?i)project.*(?:what is|structure|overview)",
                    "(?i)introduce.*project", "(?i)tell me about.*project", "(?i)understand.*project"),
            Map.of()),

        rule(IntentTypeEnumVO.SEARCH,
            List.of("找", "搜索", "查找", "哪个进程", "哪个文件",
                    "find", "search", "grep", "locate", "look up", "which process", "which file"),
            List.of("(?:找|搜索).*(?:文件|进程|端口)", "(?i)(?:find|search).*(?:file|process|port)"),
            Map.of())
    );

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        return classify(message, context, null);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId) {
        // Rule classification does not depend on a model; modelId/recentUserMessages are ignored
        return classifyByRules(message, context);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId,
                                   java.util.List<String> recentUserMessages) {
        return classifyByRules(message, context);
    }

    private IntentResultVO classifyByRules(String message, ConversationContextVO context) {
        String lowerMsg = message.toLowerCase();
        if (message.matches("(?s).*(?:这(?:是什么|个是什么)项目|介绍一下(?:这个|当前)?项目|介绍.*项目|项目.*(?:结构|介绍)).*")
                || message.matches("(?is).*(?:what is this project|introduce (?:this |the current )?project|introduce.*project|project.*(?:structure|overview|intro)|tell me about (?:this |the )?project).*")) {
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.EXPLAIN)
                .confidence(0.95)
                .entities(Map.of("project", "current"))
                .build();
        }

        IntentResultVO best = IntentResultVO.builder()
            .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();

        for (IntentRuleVO rule : RULES) {
            double score = 0.0;

            // Keyword match (max 0.6)
            long hits = rule.getKeywords().stream()
                .filter(lowerMsg::contains).count();
            score += Math.min(0.6, hits * 0.2);

            // Regex match (extra +0.2)
            boolean patternHit = rule.getPatterns().stream()
                .anyMatch(p -> Pattern.matches(".*" + p + ".*", message));
            if (patternHit) score += 0.2;

            // Context boost: +0.1 if a recent intent matches
            if (context != null && context.getRecentIntents() != null) {
                if (context.getRecentIntents().stream()
                    .anyMatch(h -> h.getIntent() == rule.getIntent())) {
                    score += 0.1;
                }
            }

            score = Math.min(1.0, score);

            if (score > best.getConfidence()) {
                best = IntentResultVO.builder()
                    .intent(rule.getIntent())
                    .confidence(score)
                    .entities(extractEntities(message, rule.getIntent()))
                    .build();
            }
        }
        return best;
    }

    private Map<String, String> extractEntities(String message, IntentTypeEnumVO intent) {
        Map<String, String> entities = new HashMap<>();
        // Extract service names
        List<String> services = List.of("nginx", "redis", "mysql", "postgres", "docker",
            "kafka", "rabbitmq", "elasticsearch", "tomcat", "spring");
        services.stream().filter(message.toLowerCase()::contains)
            .forEach(svc -> entities.put("service", svc));
        return entities;
    }

    private static IntentRuleVO rule(IntentTypeEnumVO intent, List<String> keywords,
                                   List<String> patterns, Map<List<String>, Double> contextBoost) {
        IntentRuleVO r = new IntentRuleVO();
        r.setIntent(intent);
        r.setKeywords(keywords);
        r.setPatterns(patterns);
        r.setContextBoost(contextBoost);
        return r;
    }
}
