package io.rwa.server.auth;

import io.rwa.server.common.ApiException;
import io.rwa.server.wallet.WalletService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
public class AdminUserQueryController {

    private final WalletService walletService;

    public AdminUserQueryController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/by-external")
    public Map<String, Object> byExternal(
        @RequestParam("externalUserId") String externalUserId,
        @RequestParam(value = "provider", defaultValue = "MEMBER") String provider
    ) {
        WalletService.ExternalUserWalletView view = walletService.findByExternalUser(provider, externalUserId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User link not found"));
        return Map.of(
            "userId", view.userId(),
            "address", view.address(),
            "externalUserId", view.externalUserId(),
            "provider", view.provider(),
            "complianceStatus", view.complianceStatus().name(),
            "complianceUpdatedAt", view.complianceUpdatedAt()
        );
    }
}
