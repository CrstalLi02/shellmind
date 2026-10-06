package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Task-breakdown DTO
 *
 * <p>When the user asks a complex task, the AI splits it into ordered subtasks,
 * each of which can run independently with tracked status.
 *
 * <p>Matches ShellMind's Task Breakdown pattern:
 * detect a complex task → propose a split → user confirms → execute in order
 *
 * @author shellmind dev
 * 2026/6/19
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskBreakdownDTO {

    /**
     * Original user request
     */
    private String originalRequest;

    /**
     * Split subtask list
     */
    private List<SubTask> subTasks;

    /**
     * Whether user confirmation is required
     */
    @Builder.Default
    private boolean needConfirmation = true;

    /**
     * Breakdown summary (one sentence for the overall plan)
     */
    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SubTask {

        /**
         * Subtask index (1-based)
         */
        private int index;

        /**
         * Subtask title
         */
        private String title;

        /**
         * Subtask description
         */
        private String description;

        /**
         * Expected tools
         */
        private String expectedTools;

        /**
         * Subtask status: pending / executing / completed / failed / skipped
         */
        @Builder.Default
        private String status = "pending";

        /**
         * Execution result (filled when complete)
         */
        private String result;
    }

}
