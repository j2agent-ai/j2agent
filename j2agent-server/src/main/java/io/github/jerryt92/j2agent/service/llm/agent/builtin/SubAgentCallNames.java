package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import org.apache.commons.lang3.StringUtils;

/**
 * 子智能体调用工具名常量（编排器 Hook 模拟 TOOL 事件）。
 */
public final class SubAgentCallNames {

    public static final String TOOL_NAME = "call_sub_agent";

    private SubAgentCallNames() {
    }

    public static boolean isSubAgentCallToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }
        return TOOL_NAME.equals(toolName.trim());
    }
}
