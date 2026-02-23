package io.rwa.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.config.RwaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ServiceTokenFilter extends OncePerRequestFilter {

    private final RwaProperties properties;
    private final ObjectMapper objectMapper;
    private final PathPatternMatcher pathPatternMatcher = new PathPatternMatcher();

    public ServiceTokenFilter(RwaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!pathPatternMatcher.matchesAny(path, SecurityPropertyResolver.internalPaths(properties))) {
            filterChain.doFilter(request, response);
            return;
        }

        String configuredToken = SecurityPropertyResolver.serviceToken(properties);
        if (configuredToken == null || configuredToken.isBlank()) {
            writeError(response, 500, "SERVICE_TOKEN is not configured");
            return;
        }

        String headerName = SecurityPropertyResolver.serviceTokenHeader(properties);
        String provided = request.getHeader(headerName);
        if (!configuredToken.equals(provided)) {
            writeError(response, 401, "Invalid service token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.createObjectNode()
            .put("status", status)
            .put("message", message)
            .toString());
    }
}
