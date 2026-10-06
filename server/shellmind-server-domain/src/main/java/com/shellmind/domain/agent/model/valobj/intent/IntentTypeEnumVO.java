package com.shellmind.domain.agent.model.valobj.intent;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum IntentTypeEnumVO {
    DIAGNOSE("Diagnose a problem"),
    CONFIGURE("Configuration change"),
    DEPLOY("Deploy operation"),
    MONITOR("Monitoring lookup"),
    SECURITY("Security-related"),
    BACKUP("Backup and restore"),
    EXECUTE("Execute directly"),
    EXPLAIN("Explanation"),
    SEARCH("Search"),
    CHAT("Small talk"),
    CONTINUE("Continue"),
    UNKNOWN("Unknown");

    private final String label;
}
