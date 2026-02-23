package io.rwa.server.security;

import io.rwa.server.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

public final class UserPrincipalContext {

    public static final String REQUEST_ATTR = UserPrincipal.class.getName();

    private UserPrincipalContext() {
    }

    public static UserPrincipal require(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTR);
        if (value instanceof UserPrincipal principal) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }
}
