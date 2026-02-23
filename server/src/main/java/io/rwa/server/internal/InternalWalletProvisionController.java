package io.rwa.server.internal;

import io.rwa.server.wallet.WalletService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/wallets")
public class InternalWalletProvisionController {

    private final WalletService walletService;

    public InternalWalletProvisionController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/provision")
    public Map<String, Object> provision(
        @Valid @RequestBody InternalWalletProvisionRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        WalletService.WalletProvisionResult result = walletService.provisionWallet(
            request.userId(),
            request.provider(),
            request.externalUserId()
        );
        return Map.of(
            "userId", result.userId(),
            "address", result.address(),
            "complianceStatus", result.complianceStatus().name()
        );
    }
}
