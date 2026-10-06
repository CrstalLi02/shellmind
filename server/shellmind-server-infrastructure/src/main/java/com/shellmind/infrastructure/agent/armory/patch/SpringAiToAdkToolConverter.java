package com.shellmind.infrastructure.agent.armory.patch;

import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Converter from Spring AI ToolCallback to Google ADK FunctionTool.
 *
 * Extracts toolMethod and toolObject from Spring AI MethodToolCallback via reflection,
 * then converts them with FunctionTool.create(obj, methodName).
 */
@Slf4j
@Component
public class SpringAiToAdkToolConverter {

    /**
     * Convert a list of Spring AI ToolCallbacks to ADK FunctionTools.
     */
    public List<Object> convert(List<ToolCallback> toolCallbacks) {
        List<Object> adkTools = new ArrayList<>();
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return adkTools;
        }

        for (ToolCallback callback : toolCallbacks) {
            try {
                Object adkTool = convertSingle(callback);
                if (adkTool != null) {
                    adkTools.add(adkTool);
                }
            } catch (Exception e) {
                log.warn("Failed to convert ToolCallback: {}, reason: {}", callback.getClass().getName(), e.getMessage(), e);
            }
        }

        return adkTools;
    }

    private Object convertSingle(ToolCallback callback) {
        // Check whether this is a MethodToolCallback
        if (!callback.getClass().getName().contains("MethodToolCallback")) {
            log.warn("Unsupported ToolCallback type: {}", callback.getClass().getName());
            return null;
        }

        ToolDefinition def = callback.getToolDefinition();
        String toolName = def != null ? def.name() : "unknown";

        // Read toolMethod and toolObject via reflection
        Method toolMethod = null;
        Object toolObject = null;

        try {
            toolMethod = (Method) getPrivateField(callback, "toolMethod");
            toolObject = getPrivateField(callback, "toolObject");
        } catch (Exception e) {
            log.warn("Failed to read fields via reflection: {}", e.getMessage());
            return null;
        }

        if (toolMethod == null || toolObject == null) {
            log.warn("toolMethod or toolObject was not found");
            return null;
        }

        log.info("Converting tool: name={}, method={}, object={}",
                toolName, toolMethod.getName(), toolObject.getClass().getName());

        try {
            FunctionTool functionTool = FunctionTool.create(toolObject, toolMethod.getName());
            log.info("FunctionTool created: name={}", functionTool.name());
            return functionTool;
        } catch (Exception e) {
            log.warn("FunctionTool.create failed: {}", e.getMessage());
            return null;
        }
    }

    private Object getPrivateField(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals(fieldName)) {
                    try {
                        field.setAccessible(true);
                        return field.get(obj);
                    } catch (Exception e) {
                        log.debug("Failed to read field {}: {}", fieldName, e.getMessage());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
