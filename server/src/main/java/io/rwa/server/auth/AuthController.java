package io.rwa.server.auth;

import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import io.rwa.server.security.LocalJwtIssuer;
import io.rwa.server.wallet.SignupResult;
import io.rwa.server.wallet.WalletService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final WalletService walletService;
    private final LocalJwtIssuer localJwtIssuer;
    private final RwaProperties properties;

    public AuthController(WalletService walletService, LocalJwtIssuer localJwtIssuer, RwaProperties properties) {
        this.walletService = walletService;
        this.localJwtIssuer = localJwtIssuer;
        this.properties = properties;
    }

    @PostMapping("/auth/signup_local")
    public Map<String, Object> signupLocal(@Valid @RequestBody AuthSignupRequest request) {
        ensureLocalAuthEnabled();
        SignupResult result = walletService.signup(request.externalUserId(), request.provider());
        LocalJwtIssuer.IssuedToken issuedToken = localJwtIssuer.issue(
            result.userId(),
            result.provider(),
            result.externalUserId()
        );
        return Map.of(
            "userId", result.userId(),
            "address", result.address(),
            "externalUserId", result.externalUserId(),
            "provider", result.provider(),
            "created", result.created(),
            "accessToken", issuedToken.accessToken(),
            "tokenType", "Bearer",
            "expiresInSeconds", issuedToken.expiresInSeconds()
        );
    }

    private void ensureLocalAuthEnabled() {
        String mode = properties.getAuth() == null ? null : properties.getAuth().getMode();
        boolean localEnabled = properties.getAuth() != null && properties.getAuth().isLocalAuthEnabled();
        if (!"LOCAL".equalsIgnoreCase(mode) || !localEnabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
