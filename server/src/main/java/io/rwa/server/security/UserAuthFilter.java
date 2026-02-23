package io.rwa.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UserAuthFilter extends OncePerRequestFilter {

    private final RwaProperties properties;
    private final ObjectMapper objectMapper;
    private final PathPatternMatcher pathPatternMatcher = new PathPatternMatcher();
    private final JwksJwtVerifier jwksJwtVerifier;
    private final HmacJwtVerifier hmacJwtVerifier;

    public UserAuthFilter(
        RwaProperties properties,
        ObjectMapper objectMapper,
        JwksJwtVerifier jwksJwtVerifier,
        HmacJwtVerifier hmacJwtVerifier
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jwksJwtVerifier = jwksJwtVerifier;
        this.hmacJwtVerifier = hmacJwtVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (!pathPatternMatcher.matchesAny(path, SecurityPropertyResolver.authRequiredPaths(properties))) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "Bearer token required");
            return;
        }

        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "Bearer token required");
            return;
        }

        try {
            JwtVerifier verifier = resolveVerifier();
            VerifiedJwt verified = verifier.verify(token);
            UserPrincipal principal = new UserPrincipal(verified.userId(), verified.provider(), verified.externalUserId());
            request.setAttribute(UserPrincipalContext.REQUEST_ATTR, principal);
            filterChain.doFilter(request, response);
        } catch (ApiException e) {
            writeError(response, e.getStatus().value(), e.getMessage());
        }
    }

    private JwtVerifier resolveVerifier() {
        String mode = SecurityPropertyResolver.authMode(properties);
        return switch (mode) {
            case "JWKS" -> jwksJwtVerifier;
            case "HMAC", "LOCAL" -> hmacJwtVerifier;
            default -> throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unsupported AUTH_MODE: " + mode);
        };
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
