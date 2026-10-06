package com.shellmind.domain.agent.model.valobj.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Task-breakdown value object (used inside the domain layer).
 *
 * @author shellmind dev
 * 2026/6/19
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskBreakdownVO {

    private String originalRequest;
    private List<SubTask> subTasks;
    @Builder.Default
    private boolean needConfirmation = true;
    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SubTask {
        private int index;
        private String title;
        private String description;
        private String expectedTools;
        @Builder.Default
        private String status = "pending";
        private String result;
    }
}
