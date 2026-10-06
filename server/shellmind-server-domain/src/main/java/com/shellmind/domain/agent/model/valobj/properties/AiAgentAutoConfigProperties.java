package com.shellmind.domain.agent.model.valobj.properties;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * Whether AI Agent auto-assembly is enabled
     */
    private boolean enabled = false;

    private Map<String, AiAgentConfigTableVO> tables;

}
