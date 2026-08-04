package io.github.jerryt92.j2agent.service.llm.agent.builtin.universalagent;

import io.github.jerryt92.j2agent.model.I18nString;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import io.github.jerryt92.j2agent.tools.WebTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 平台内置通用助手：子智能体调度由 {@link UniversalAssistantOrchestratorService} 在 {@link io.github.jerryt92.j2agent.service.llm.ChatService} 前置处理；
 * 无委派时本 Agent 以标准 ReAct + {@link #loadSystemPrompt()} 直接回答用户。
 */
@Component
public class UniversalAssistantAgent extends AiAgent {

    @Autowired
    private WebTool webTool;

    @Override
    public String getAgentId() {
        return UniversalAssistantConstants.AGENT_ID;
    }

    @Override
    public I18nString getAgentName() {
        return new I18nString()
                .zhCN(UniversalAssistantConstants.DISPLAY_NAME)
                .enUS("Universal AI Assistant");
    }

    @Override
    public I18nString getAgentDescription() {
        return new I18nString()
                .zhCN("平台通用助手，可自动委派子智能体处理专业问题。")
                .enUS("Platform assistant that can automatically delegate specialized tasks to sub-agents.");
    }

    @Override
    public int getSort() {
        return 0;
    }

    @Override
    public boolean isQaTemplateEnabled() {
        return true;
    }

    @Override
    protected String getQaTemplateResourcePath() {
        return "prompts/universal-assistant-qa-template.json";
    }

    @Override
    protected Object[] buildTools() {
        return new Object[]{webTool};
    }

    @Override
    public String loadSystemPrompt() {
        return """
                你是 J2Agent AI 平台的 AI 通用助手。
                1. 专业任务由平台编排服务在 ReAct 开始前自动完成子智能体召回、调度与调用；委派时子智能体流式输出即为用户可见答复，无需你再汇总或改写。
                2. 当本回合无子智能体被调用时（寒暄、常识、与全部专业智能体无关的问题），由你直接回答用户。
                3. 当用户意图有歧义，或缺少目标、对象、范围等关键条件时，禁止猜测，也禁止用普通文本反问；必须调用 ask_question 工具提出简短、具体的澄清问题，一次只询问完成判断所必需的信息。
                4. ask_question 的 optionsJson 只包含真实业务候选答案，不要加入“自定义”“其他”“以上都不是”等兜底项；调用成功后立即结束本轮并等待用户回答。
                5. 使用与用户相同的语言（中文问题则中文回答）。
                6. 不要臆造专业领域知识；需要知识库、报表等专业能力时，依赖平台已委派的子智能体，勿编造流程或数据。
                7. 除 ask_question 外你没有其它可调用的业务工具；子智能体调度由平台编排服务在 ChatService 层自动完成。""";
    }
}
