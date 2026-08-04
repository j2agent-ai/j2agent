package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent;
import io.github.jerryt92.j2agent.mapper.ext.ApiProviderConfigExtMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ApiProviderConfigPoMapper;
import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * 模型提供商配置 CRUD 与「设为当前」事务。
 *
 * <p>所有变更都会发布 {@link ProviderConfigChangedEvent}，由运行时监听重载。
 * 返回给上层的 {@code config} 中 {@code apiKey} 会被脱敏。
 */
@Slf4j
@Service
@DependsOn("flywayInitializer")
public class ApiProviderConfigService {

    /** Embedding 批量大小默认值 */
    private static final int DEFAULT_EMBEDDING_BATCH_SIZE = 10;
    /** Embedding 批量大小上限 */
    private static final int MAX_EMBEDDING_BATCH_SIZE = 128;

    /** apiKey 脱敏后保留的末尾字符数 */
    private static final int API_KEY_TAIL_VISIBLE = 4;

    private final ApiProviderConfigPoMapper mapper;
    private final ApiProviderConfigExtMapper extMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ApiProviderConfigService(ApiProviderConfigPoMapper mapper,
                                    ApiProviderConfigExtMapper extMapper,
                                    ObjectMapper objectMapper,
                                    ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.extMapper = extMapper;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 按 api_type 列出全部配置；返回的 config 字段中 apiKey 已脱敏。
     */
    public List<ProviderConfigView> list(String apiType) {
        validateApiType(apiType);
        List<ApiProviderConfigPo> rows = extMapper.selectByApiType(apiType);
        return rows.stream().map(this::toView).toList();
    }

    /**
     * 按 id 查询单条；apiKey 已脱敏。
     */
    public ProviderConfigView getById(String id) {
        ApiProviderConfigPo po = requirePo(id);
        return toView(po);
    }

    /**
     * 新建一条配置；若 {@code makeCurrent=true}，会清除同 apiType 其余 current 标记并将其设为当前。
     */
    @Transactional
    public ProviderConfigView create(String apiType,
                                     String configName,
                                     String providerType,
                                     Map<String, Object> config,
                                     String description,
                                     boolean enabled,
                                     boolean makeCurrent) {
        validateApiType(apiType);
        validateProvider(apiType, providerType);
        validateConfigName(configName);

        long now = System.currentTimeMillis();
        ApiProviderConfigPo po = new ApiProviderConfigPo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setApiType(apiType);
        po.setConfigName(configName);
        po.setProviderType(providerType);
        po.setConfigJson(writeJson(sanitizeConfig(config, apiType, providerType)));
        po.setEnabled((short) 1);
        po.setIsCurrent((short) 0);
        po.setDescription(description);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        mapper.insert(po);

        boolean switched = false;
        if (makeCurrent) {
            switched = true;
            extMapper.clearCurrentByApiType(apiType);
            extMapper.markCurrent(po.getId(), now);
            po.setIsCurrent((short) 1);
        }
        boolean embeddingRuntimeChanged = isEmbeddingRuntimeChangedOnCreate(apiType, switched);
        publish(apiType, switched, embeddingRuntimeChanged);
        return toView(po);
    }

    /**
     * 更新一条配置；apiKey 留空表示沿用原密钥。
     */
    @Transactional
    public ProviderConfigView update(String id,
                                     String configName,
                                     String providerType,
                                     Map<String, Object> config,
                                     String description,
                                     boolean enabled) {
        ApiProviderConfigPo old = requirePo(id);
        String apiType = old.getApiType();
        validateProvider(apiType, providerType);
        validateConfigName(configName);

        Map<String, Object> oldConfig = readJson(old.getConfigJson());
        String oldProviderType = old.getProviderType();
        boolean wasCurrent = shortEquals(old.getIsCurrent(), 1);

        Map<String, Object> merged = mergeConfigPreservingApiKey(old, config);
        merged = sanitizeConfig(merged, apiType, providerType);

        long now = System.currentTimeMillis();
        old.setConfigName(configName);
        old.setProviderType(providerType);
        old.setConfigJson(writeJson(merged));
        old.setEnabled((short) 1);
        old.setDescription(description);
        old.setUpdateTime(now);
        mapper.updateByPrimaryKey(old);

        boolean embeddingRuntimeChanged = isEmbeddingRuntimeChangedOnUpdate(
                apiType, wasCurrent, false, oldProviderType, oldConfig, providerType, merged);
        publish(apiType, false, embeddingRuntimeChanged);
        return toView(old);
    }

    /**
     * 删除一条配置；当前生效的记录禁止删除，需先切换。
     */
    @Transactional
    public void delete(String id) {
        ApiProviderConfigPo po = requirePo(id);
        if (shortEquals(po.getIsCurrent(), 1)) {
            throw new IllegalStateException("当前生效配置不可删除，请先切换其他配置为当前");
        }
        mapper.deleteByPrimaryKey(id);
        publish(po.getApiType(), false, false);
    }

    /**
     * 复制一条配置；新配置默认启用且非当前生效，完整保留源 config_json（含 apiKey）。
     */
    @Transactional
    public ProviderConfigView copy(String id) {
        ApiProviderConfigPo source = requirePo(id);
        Map<String, Object> config = readJson(source.getConfigJson());
        String newName = deriveCopyName(source.getConfigName());
        return create(
                source.getApiType(),
                newName,
                source.getProviderType(),
                config,
                source.getDescription(),
                true,
                false);
    }

    /**
     * 将指定 id 设为该 api_type 下当前生效配置；目标必须启用。
     */
    @Transactional
    public ProviderConfigView activate(String id) {
        ApiProviderConfigPo po = requirePo(id);
        long now = System.currentTimeMillis();
        extMapper.clearCurrentByApiType(po.getApiType());
        extMapper.markCurrent(id, now);
        po.setIsCurrent((short) 1);
        po.setUpdateTime(now);
        boolean embeddingRuntimeChanged = ProviderTypes.API_TYPE_EMBEDDING.equals(po.getApiType());
        publish(po.getApiType(), true, embeddingRuntimeChanged);
        return toView(po);
    }

    /**
     * 合并配置：若入参 apiKey 为空且原 JSON 中存在 apiKey，则沿用旧密钥。
     */
    private Map<String, Object> mergeConfigPreservingApiKey(ApiProviderConfigPo old,
                                                            Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (incoming != null) {
            merged.putAll(incoming);
        }
        Object incomingKey = merged.get("apiKey");
        boolean incomingBlank = incomingKey == null
                || (incomingKey instanceof String s && s.isEmpty());
        if (incomingBlank) {
            String oldKey = extractApiKey(old.getConfigJson());
            if (oldKey != null) {
                merged.put("apiKey", oldKey);
            }
        }
        return merged;
    }

    private String extractApiKey(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Object v = map.get("apiKey");
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            log.warn("读取旧 config_json apiKey 失败", e);
            return null;
        }
    }

    /**
     * 视图对象：apiKey 已脱敏。
     */
    private ProviderConfigView toView(ApiProviderConfigPo po) {
        Map<String, Object> config = readJson(po.getConfigJson());
        Object apiKey = config.get("apiKey");
        if (apiKey instanceof String s) {
            config.put("apiKey", maskApiKey(s));
        }
        return new ProviderConfigView(
                po.getId(),
                po.getApiType(),
                po.getConfigName(),
                po.getProviderType(),
                config,
                shortEquals(po.getEnabled(), 1),
                shortEquals(po.getIsCurrent(), 1),
                po.getDescription(),
                po.getCreateTime(),
                po.getUpdateTime()
        );
    }

    private void publish(String apiType, boolean activeSwitched, boolean embeddingRuntimeChanged) {
        eventPublisher.publishEvent(new ProviderConfigChangedEvent(apiType, activeSwitched, embeddingRuntimeChanged));
    }

    private static boolean isEmbeddingRuntimeChangedOnCreate(String apiType, boolean switchedToCurrent) {
        return ProviderTypes.API_TYPE_EMBEDDING.equals(apiType) && switchedToCurrent;
    }

    private static boolean isEmbeddingRuntimeChangedOnUpdate(String apiType,
                                                             boolean wasCurrent,
                                                             boolean willDisableCurrent,
                                                             String oldProviderType,
                                                             Map<String, Object> oldConfig,
                                                             String newProviderType,
                                                             Map<String, Object> newConfig) {
        if (!ProviderTypes.API_TYPE_EMBEDDING.equals(apiType)) {
            return false;
        }
        if (willDisableCurrent) {
            return true;
        }
        if (!wasCurrent) {
            return false;
        }
        if (!Objects.equals(oldProviderType, newProviderType)) {
            return true;
        }
        return embeddingRuntimeConfigChanged(oldConfig, newConfig);
    }

    private static boolean embeddingRuntimeConfigChanged(Map<String, Object> oldConfig,
                                                         Map<String, Object> newConfig) {
        return !Objects.equals(stringValue(oldConfig, "modelName"), stringValue(newConfig, "modelName"))
                || !Objects.equals(stringValue(oldConfig, "baseUrl"), stringValue(newConfig, "baseUrl"))
                || !Objects.equals(stringValue(oldConfig, "embeddingsPath"), stringValue(newConfig, "embeddingsPath"))
                || !Objects.equals(stringValue(oldConfig, "apiKey"), stringValue(newConfig, "apiKey"));
    }

    private static String stringValue(Map<String, Object> config, String key) {
        if (config == null) {
            return null;
        }
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private ApiProviderConfigPo requirePo(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        ApiProviderConfigPo po = mapper.selectByPrimaryKey(id);
        if (po == null) {
            throw new IllegalArgumentException("找不到配置: id=" + id);
        }
        return po;
    }

    private void validateApiType(String apiType) {
        if (!ProviderTypes.isSupportedApiType(apiType)) {
            throw new IllegalArgumentException("不支持的 apiType: " + apiType);
        }
    }

    private void validateProvider(String apiType, String providerType) {
        if (!ProviderTypes.isSupportedProvider(apiType, providerType)) {
            throw new IllegalArgumentException("不支持的 providerType: " + providerType + "（apiType=" + apiType + "）");
        }
    }

    private void validateConfigName(String configName) {
        if (StringUtils.isBlank(configName)) {
            throw new IllegalArgumentException("configName 不能为空");
        }
        if (configName.length() > 128) {
            throw new IllegalArgumentException("configName 长度超过 128");
        }
    }

    /** 复制配置时生成新名称，后缀为「 (副本)」，总长不超过 128。 */
    static String deriveCopyName(String original) {
        String suffix = " (副本)";
        int maxLen = 128;
        String base = original == null ? "" : original;
        String candidate = base + suffix;
        if (candidate.length() <= maxLen) {
            return candidate;
        }
        return base.substring(0, maxLen - suffix.length()) + suffix;
    }

    /**
     * 规范化 config：Ollama 空 contextLength 不写入；非 Ollama 剔除 contextLength；非 Anthropic 或无效 maxTokens 剔除。
     */
    private Map<String, Object> sanitizeConfig(Map<String, Object> config, String apiType, String providerType) {
        if (config == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>(config);
        if (ProviderTypes.LLM_OLLAMA.equals(providerType)) {
            Object ctx = copy.get("contextLength");
            if (ctx == null || isNonPositiveNumber(ctx)) {
                copy.remove("contextLength");
            }
        } else {
            copy.remove("contextLength");
        }
        if (!ProviderTypes.LLM_ANTHROPIC.equals(providerType) || readPositiveInteger(copy, "maxTokens") == null) {
            copy.remove("maxTokens");
        }
        sanitizeThinkingFields(copy, providerType);
        if (ProviderTypes.API_TYPE_EMBEDDING.equals(apiType)) {
            sanitizeEmbeddingBatchSize(copy);
        } else {
            copy.remove("embeddingBatchSize");
        }
        copy.remove("useRag");
        copy.remove("useTools");
        copy.remove("useMcpTools");
        copy.remove("chatMemoryDualRead");
        return copy;
    }

    /**
     * 规范化 Embedding 批量大小：无效值剔除，有效值 clamp 到 1–128。
     */
    private static void sanitizeEmbeddingBatchSize(Map<String, Object> copy) {
        Integer batchSize = readPositiveInteger(copy, "embeddingBatchSize");
        if (batchSize == null) {
            copy.remove("embeddingBatchSize");
            return;
        }
        int clamped = Math.min(Math.max(batchSize, 1), MAX_EMBEDDING_BATCH_SIZE);
        if (clamped == DEFAULT_EMBEDDING_BATCH_SIZE) {
            copy.remove("embeddingBatchSize");
        } else {
            copy.put("embeddingBatchSize", clamped);
        }
    }

    /**
     * 深度思考：不支持的提供商剔除字段；支持的规范 thinkingMode，非 on 时剔除 budget。
     */
    private static void sanitizeThinkingFields(Map<String, Object> copy, String providerType) {
        if (!LlmThinkingSupport.supports(providerType)) {
            copy.remove("thinkingMode");
            copy.remove("thinkingBudgetTokens");
            return;
        }
        Object rawMode = copy.get("thinkingMode");
        String mode = LlmThinkingSupport.normalizeMode(rawMode == null ? null : rawMode.toString());
        if (LlmThinkingSupport.MODE_PROVIDER_DEFAULT.equals(mode)) {
            copy.remove("thinkingMode");
        } else {
            copy.put("thinkingMode", mode);
        }
        if (!LlmThinkingSupport.MODE_ON.equals(mode)) {
            copy.remove("thinkingBudgetTokens");
            return;
        }
        Integer budget = readPositiveInteger(copy, "thinkingBudgetTokens");
        if (budget == null) {
            copy.remove("thinkingBudgetTokens");
        } else {
            copy.put("thinkingBudgetTokens", budget);
        }
    }

    private static boolean isNonPositiveNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue() <= 0;
        }
        if (value instanceof String s) {
            if (StringUtils.isBlank(s)) {
                return true;
            }
            try {
                return Double.parseDouble(s.trim()) <= 0;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }

    /** 从 config 读取正整数；缺失、非数字或 ≤0 时返回 null。 */
    private static Integer readPositiveInteger(Map<String, Object> config, String key) {
        if (config == null || key == null) {
            return null;
        }
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        if (value instanceof String s) {
            if (StringUtils.isBlank(s)) {
                return null;
            }
            try {
                int v = Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String writeJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("配置 JSON 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("读取 config_json 失败，返回空对象", e);
            return new LinkedHashMap<>();
        }
    }

    private static boolean shortEquals(Short v, int target) {
        return v != null && v == target;
    }

    private static String maskApiKey(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        int len = raw.length();
        if (len <= API_KEY_TAIL_VISIBLE) {
            return "****";
        }
        return "****" + raw.substring(len - API_KEY_TAIL_VISIBLE);
    }

    /**
     * 视图对象：列表与详情接口返回的通用形态。
     */
    public record ProviderConfigView(
            String id,
            String apiType,
            String configName,
            String providerType,
            Map<String, Object> config,
            boolean enabled,
            boolean isCurrent,
            String description,
            Long createTime,
            Long updateTime
    ) {
    }
}
