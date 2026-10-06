package com.shellmind.cases.command;

import com.shellmind.api.dto.CommandResultDTO;
import com.shellmind.domain.agent.model.valobj.command.CommandResult;
import com.shellmind.domain.agent.service.command.CommandDispatcher;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local-command callback use case: the client posts results after running a dispatched command, or pulls cached results after reconnect.
 */
@Service
public class LocalCommandCase {

    @Resource
    private CommandDispatcher commandDispatcher;

    @Resource
    private AgentRunRegistry runRegistry;

    public void complete(CommandResultDTO result) {
        commandDispatcher.completeCommand(result.getCmdId(), toDomain(result));
    }

    /** @return cached result, or null if missing/expired */
    public CommandResultDTO poll(String cmdId) {
        CommandResult result = commandDispatcher.pollResult(cmdId);
        return result == null ? null : toDto(result);
    }

    public Map<String, CommandResultDTO> pollAll() {
        Map<String, CommandResultDTO> results = new LinkedHashMap<>();
        commandDispatcher.pollAllResults().forEach((cmdId, result) -> results.put(cmdId, toDto(result)));
        return results;
    }

    public Map<String, Object> status() {
        return Map.of(
                "pendingCommands", commandDispatcher.getPendingCount(),
                "clientConnected", runRegistry.hasClientChannel()
        );
    }

    private CommandResult toDomain(CommandResultDTO dto) {
        return CommandResult.builder()
                .cmdId(dto.getCmdId())
                .sessionId(dto.getSessionId())
                .status(dto.getStatus() == null ? null : CommandResult.Status.valueOf(dto.getStatus().name()))
                .output(dto.getOutput())
                .exitCode(dto.getExitCode())
                .durationMs(dto.getDurationMs())
                .error(dto.getError())
                .success(dto.isSuccess())
                .build();
    }

    private CommandResultDTO toDto(CommandResult result) {
        return CommandResultDTO.builder()
                .cmdId(result.getCmdId())
                .sessionId(result.getSessionId())
                .status(result.getStatus() == null ? null : CommandResultDTO.Status.valueOf(result.getStatus().name()))
                .output(result.getOutput())
                .exitCode(result.getExitCode())
                .durationMs(result.getDurationMs())
                .error(result.getError())
                .success(result.isSuccess())
                .build();
    }
}
