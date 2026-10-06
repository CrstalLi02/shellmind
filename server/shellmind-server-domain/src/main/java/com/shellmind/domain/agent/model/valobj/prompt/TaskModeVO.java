package com.shellmind.domain.agent.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TaskModeVO {
    EXECUTE("Execute a task"),
    STATUS("Confirm or query status"),
    CLARIFY("Needs clarification"),
    CONVERSATIONAL("Ordinary Q&A");

    private final String label;
}
