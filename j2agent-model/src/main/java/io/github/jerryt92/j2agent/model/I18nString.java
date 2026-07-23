package io.github.jerryt92.j2agent.model;

/**
 * Agent 内部使用的国际化字符串实体；API 对外返回已按当前语言解析后的普通字符串。
 */
public class I18nString {
    private String zhCN;
    private String enUS;

    public String getZhCN() {
        return zhCN;
    }

    public void setZhCN(String zhCN) {
        this.zhCN = zhCN;
    }

    public String getEnUS() {
        return enUS;
    }

    public void setEnUS(String enUS) {
        this.enUS = enUS;
    }

    public I18nString zhCN(String zhCN) {
        this.zhCN = zhCN;
        return this;
    }

    public I18nString enUS(String enUS) {
        this.enUS = enUS;
        return this;
    }
}
