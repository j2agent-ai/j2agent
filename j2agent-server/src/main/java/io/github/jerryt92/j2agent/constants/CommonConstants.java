package io.github.jerryt92.j2agent.constants;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CommonConstants {
    @Getter
    private static String springApplicationName;

    @Value("${spring.application.name}")
    private void setSpringApplicationName(String springApplicationName) {
        CommonConstants.springApplicationName = springApplicationName;
    }

    public static final String FILE_URL = "/v1/rest/j2agent/file/";

    public static final String STATIC_FILE_URL = FILE_URL + "static/";

    /** 知识库仓库文件直链前缀 */
    public static final String REPO_FILE_URL = FILE_URL + "repo/";

    public static final String ZH_CN = "zh_CN";

    public static final String EN_US = "en_US";

    public static final String ENGLISH_LLM_PROMPT = "Please output content in English.";
}
