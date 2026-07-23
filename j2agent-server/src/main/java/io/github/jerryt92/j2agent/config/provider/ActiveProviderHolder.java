package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.mapper.ext.ApiProviderConfigExtMapper;
import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有当前生效的 LLM / Embedding 解析后配置，供运行时（ChatModel、EmbeddingService 等）订阅式读取。
 *
 * <p>启动时从 {@code api_provider_config} 表加载 {@code is_current=1 且 enabled=1} 的记录；
 * 收到 {@link io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent} 后由
 * {@code AiRuntimeReloadService} 调用 {@link #reloadFromDb()} 刷新。
 */
@Slf4j
@Component
@DependsOn("flywayInitializer")
public class ActiveProviderHolder {

    private final ApiProviderConfigExtMapper extMapper;
    private final ObjectMapper objectMapper;

    private final AtomicReference<LlmActiveConfig> activeLlm = new AtomicReference<>();
    private final AtomicReference<EmbeddingActiveConfig> activeEmbedding = new AtomicReference<>();

    public ActiveProviderHolder(ApiProviderConfigExtMapper extMapper,
                                ObjectMapper objectMapper) {
        this.extMapper = extMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        reloadFromDb();
    }

    /**
     * 重新从数据库加载当前 LLM / Embedding 生效配置；不抛出异常，失败时保留旧值。
     */
    public synchronized void reloadFromDb() {
        try {
            ApiProviderConfigPo llmPo = extMapper.selectCurrentByApiType(ProviderTypes.API_TYPE_LLM);
            activeLlm.set(parseLlm(llmPo));
        } catch (Exception e) {
            log.error("加载 LLM 当前配置失败，保留旧值", e);
        }
        try {
            ApiProviderConfigPo embPo = extMapper.selectCurrentByApiType(ProviderTypes.API_TYPE_EMBEDDING);
            activeEmbedding.set(parseEmbedding(embPo));
        } catch (Exception e) {
            log.error("加载 Embedding 当前配置失败，保留旧值", e);
        }
    }

    /** 当前 LLM 配置；可能为 {@code null}（无生效项或解析失败） */
    public LlmActiveConfig getActiveLlm() {
        return activeLlm.get();
    }

    /** 当前 Embedding 配置；可能为 {@code null} */
    public EmbeddingActiveConfig getActiveEmbedding() {
        return activeEmbedding.get();
    }

    /**
     * 解析单条 LLM 配置；保留 provider_type 并将 config_json 反序列化到强类型对象。
     */
    private LlmActiveConfig parseLlm(ApiProviderConfigPo po) {
        if (po == null) {
            log.warn("未找到生效中的 LLM 配置（is_current=1 且 enabled=1）");
            return null;
        }
        LlmActiveConfig cfg;
        try {
            cfg = StringUtils.isBlank(po.getConfigJson())
                    ? new LlmActiveConfig()
                    : objectMapper.readValue(po.getConfigJson(), LlmActiveConfig.class);
        } catch (Exception e) {
            log.error("解析 LLM config_json 失败，id={}", po.getId(), e);
            return null;
        }
        cfg.setId(po.getId());
        cfg.setProviderType(po.getProviderType());
        return cfg;
    }

    /**
     * 解析单条 Embedding 配置。
     */
    private EmbeddingActiveConfig parseEmbedding(ApiProviderConfigPo po) {
        if (po == null) {
            log.warn("未找到生效中的 Embedding 配置");
            return null;
        }
        EmbeddingActiveConfig cfg;
        try {
            cfg = StringUtils.isBlank(po.getConfigJson())
                    ? new EmbeddingActiveConfig()
                    : objectMapper.readValue(po.getConfigJson(), EmbeddingActiveConfig.class);
        } catch (Exception e) {
            log.error("解析 Embedding config_json 失败，id={}", po.getId(), e);
            return null;
        }
        cfg.setProviderType(po.getProviderType());
        return cfg;
    }
}
