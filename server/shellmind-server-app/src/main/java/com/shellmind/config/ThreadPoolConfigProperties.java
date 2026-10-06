package com.shellmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "thread.pool.executor.config", ignoreInvalidFields = true)
public class ThreadPoolConfigProperties {

    /** Core pool size */
    private Integer corePoolSize = 20;
    /** Max pool size */
    private Integer maxPoolSize = 200;
    /** Keep-alive time */
    private Long keepAliveTime = 10L;
    /** Max queue size */
    private Integer blockQueueSize = 5000;
    /*
     * AbortPolicy: discard the task and throw RejectedExecutionException.
     * DiscardPolicy: discard the task without throwing
     * DiscardOldestPolicy: drop the oldest queued task, then retry the rejected one
     * CallerRunsPolicy: if the pool rejects the task, the caller thread runs it
     * */
    private String policy = "AbortPolicy";

}
