package com.shellmind.domain.agent.service.context.provider;

import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import com.shellmind.domain.agent.service.prompt.dynamic.MilestoneTracker;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MilestoneProvider implements ContextProvider {
    @Resource
    private MilestoneTracker milestoneTracker;

    @Override public String getName() { return "milestoneVO"; }
    @Override public int getOrder() { return 30; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);
        result.put("milestoneVOS", milestoneVOS);
        return result;
    }
}
