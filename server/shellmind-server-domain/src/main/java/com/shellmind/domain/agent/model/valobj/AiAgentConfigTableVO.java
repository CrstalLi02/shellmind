package com.shellmind.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI agent configuration table value object.
 *
 * @author xiaofuge bugstack.cn @xiaofuge
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * Application name
     */
    private String appName;

    /**
     * Agent configuration
     */
    private Agent agent;

    /**
     * Agent module
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * Agent ID
         */
        private String agentId;

        /**
         * Agent name
         */
        private String agentName;

        /**
         * Agent description
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";

            /** Connect timeout (ms), default 10s */
            private Integer connectTimeoutMs = 10_000;
            /** Read timeout (ms), default 120s (LLM streaming generation can be slow) */
            private Integer readTimeoutMs = 120_000;
        }

        @Data
        public static class ChatModel {

            private String model;

            /**
             * Reasoning effort: minimal, low, medium, high (only for reasoning models; ignored otherwise)
             */
            private String reasoningEffort;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * Type: directory (user-configured, mapped in) or resource (placed under the project)
                 */
                private String type = "directory";

                /**
                 * Path
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * Type: loop, parallel, sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;

            /** ReAct execution-budget config (optional, overrides the default policy) */
            private ReactBudget reactBudget;
        }

        @Data
        public static class ReactBudget {
            /** Max steps */
            private Integer maxSteps;
            /** Max tool calls (total) */
            private Integer maxToolCalls;
            /** Max tool calls per round */
            private Integer maxToolCallsPerRound;
            /** Max AI-call retries */
            private Integer maxAiRetries;
            /** Default tool-execution timeout (ms) */
            private Long toolTimeoutMs;
            /** Message-history token budget (0 = default 8000) */
            private Integer contextTokenBudget;
            /** Per-call LLM timeout (ms), default 120s */
            private Long llmCallTimeoutMs;
        }
    }

}
