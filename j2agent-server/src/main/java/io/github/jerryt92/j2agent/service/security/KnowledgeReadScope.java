package io.github.jerryt92.j2agent.service.security;

import java.util.List;

/** Lexically scoped to synchronous retrieval; never retained on an executor thread after the call. */
public final class KnowledgeReadScope implements AutoCloseable {
    private static final ThreadLocal<List<String>> PREFIXES = new ThreadLocal<>();
    private final List<String> previous;
    public KnowledgeReadScope(List<String> prefixes) { previous = PREFIXES.get(); PREFIXES.set(List.copyOf(prefixes)); }
    /** 当前可读仓库范围内是否允许该 sourceFile；未设置范围时放行。 */
    public static boolean permits(String source) {
        List<String> prefixes = PREFIXES.get();
        if (prefixes == null) {
            return true;
        }
        if (source == null) {
            return false;
        }
        String normalized = source.replace('\\', '/');
        return prefixes.stream().anyMatch(normalized::startsWith);
    }
    public static List<String> repositoryCodes() {
        List<String> prefixes=PREFIXES.get();
        return prefixes==null ? null : prefixes.stream().map(p -> p.substring(0,p.length()-1)).toList();
    }
    public static String filter() {
        List<String> prefixes = PREFIXES.get();
        if (prefixes == null) return "";
        if (prefixes.isEmpty()) return "source_file == \"\" && source_file != \"\"";
        return "(" + String.join(" or ", prefixes.stream().map(p -> "source_file like \"" +
                p.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "\\%").replace("_", "\\_") + "%\"").toList()) + ")";
    }
    @Override public void close() { if (previous == null) PREFIXES.remove(); else PREFIXES.set(previous); }
}
