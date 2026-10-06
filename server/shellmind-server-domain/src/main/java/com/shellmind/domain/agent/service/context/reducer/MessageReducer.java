package com.shellmind.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

/**
 * Message-reducer interface.
 */
public interface MessageReducer {
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);
}
