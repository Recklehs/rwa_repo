package io.rwa.server.auth;

import io.rwa.server.wallet.SignupResult;
import io.rwa.server.wallet.WalletService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final WalletService walletService;

    public AuthController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/auth/signup")
    public Map<String, Object> signup(@Valid @RequestBody AuthSignupRequest request) {
        SignupResult result = walletService.signup(request.externalUserId(), request.provider());
        return Map.of(
            "userId", result.userId(),
            "address", result.address(),
            "externalUserId", result.externalUserId(),
            "provider", result.provider(),
            "created", result.created()
        );
    }
}
