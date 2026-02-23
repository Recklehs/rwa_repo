package io.rwa.server.idempotency;

import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdempotencyStatusController {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private static final String DEFAULT_SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final IdempotencyService idempotencyService;
    private final RwaProperties properties;

    public IdempotencyStatusController(IdempotencyService idempotencyService, RwaProperties properties) {
        this.idempotencyService = idempotencyService;
        this.properties = properties;
    }

    @GetMapping("/idempotency/status")
    public Map<String, Object> getStatus(
        @RequestParam("endpoint") String endpoint,
        @RequestParam("key") String key,
        HttpServletRequest request
    ) {
        ensurePrivilegedAccess(request);

        ApiIdempotencyRecord record = idempotencyService.find(endpoint, key)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Idempotency record not found"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endpoint", record.endpoint());
        data.put("idempotencyKey", record.idempotencyKey());
        data.put("status", record.status().name());
        data.put("responseStatus", record.responseStatus());
        data.put("createdAt", record.createdAt());
        data.put("updatedAt", record.updatedAt());
        return data;
    }

    private void ensurePrivilegedAccess(HttpServletRequest request) {
        if (hasValidAdminToken(request) || hasValidServiceToken(request)) {
            return;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Admin or service token required");
    }

    private boolean hasValidAdminToken(HttpServletRequest request) {
        String configured = properties.getAdminApiToken();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String provided = request.getHeader(ADMIN_TOKEN_HEADER);
        return configured.equals(provided);
    }

    private boolean hasValidServiceToken(HttpServletRequest request) {
        String configured = properties.getServiceToken();
        if (configured == null || configured.isBlank()) {
            return false;
        }

        String headerName = properties.getServiceTokenHeader();
        if (headerName == null || headerName.isBlank()) {
            headerName = DEFAULT_SERVICE_TOKEN_HEADER;
        }

        String provided = request.getHeader(headerName);
        return configured.equals(provided);
    }
}
