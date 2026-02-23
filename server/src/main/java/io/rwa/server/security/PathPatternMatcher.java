package io.rwa.server.security;

import java.util.List;
import org.springframework.util.AntPathMatcher;

public class PathPatternMatcher {

    private final AntPathMatcher matcher = new AntPathMatcher();

    public boolean matchesAny(String path, List<String> patterns) {
        if (path == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String normalized = pattern.trim();
            if (matcher.match(normalized, path)) {
                return true;
            }
            if (normalized.endsWith("/**")) {
                String basePath = normalized.substring(0, normalized.length() - 3);
                if (basePath.equals(path)) {
                    return true;
                }
            }
            if ("/**".equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
