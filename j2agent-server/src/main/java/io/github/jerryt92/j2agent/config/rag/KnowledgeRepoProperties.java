package io.github.jerryt92.j2agent.config.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库文件仓配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "j2agent.knowledge.repo")
public class KnowledgeRepoProperties {
    /**
     * 知识库根目录。
     */
    private String rootPath = "";
    /**
     * 是否在应用启动时执行知识库初始化同步。
     */
    private boolean startupSyncEnabled = true;
    /**
     * 是否开启目录监听。
     */
    private boolean watchEnabled = true;
    /**
     * 入库 content_segment 单段最大字符数；超长 query 切分窗口宽度与之对齐时使用同一语义。
     * 须在 {@code application.yaml} 的 {@code j2agent.knowledge.repo} 下配置。
     */
    private int contentSegmentChars;
    /**
     * 入库 content_segment 与超长 query 切分的相邻段重叠字符数；为 0 时无重叠。
     * 须在 {@code application.yaml} 的 {@code j2agent.knowledge.repo} 下配置。
     */
    private int contentSegmentOverlapChars;
    /**
     * 远程知识库凭据加密密钥。生产环境应显式配置并保持稳定。
     */
    private String credentialSecret = "j2agent-knowledge-repository-default-key";
}
