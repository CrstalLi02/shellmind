package com.shellmind.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentResultVO {
    private IntentTypeEnumVO intent;
    private double confidence;
    private Map<String, String> entities;
    private String rawResponse;
}
