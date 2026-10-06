package com.shellmind.domain.agent.adapter.port;

import com.shellmind.domain.agent.model.valobj.command.CommandResult;

public interface LocalCommandExecutor {

    CommandResult execute(String sessionId, String command, String cwd, long timeoutMs);
}
