package io.rwa.server.wallet;

import io.rwa.server.security.UserPrincipal;
import io.rwa.server.security.UserPrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletController {

    private final WalletTransferService walletTransferService;

    public WalletController(WalletTransferService walletTransferService) {
        this.walletTransferService = walletTransferService;
    }

    @PostMapping("/transfer/musd")
    public Map<String, Object> transferMUsd(
        @Valid @RequestBody WalletTransferRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpServletRequest
    ) {
        UserPrincipal principal = UserPrincipalContext.require(httpServletRequest);
        WalletTransferResult result = walletTransferService.transferMockUsd(principal.userId(), request, idempotencyKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("outboxId", result.outboxId());
        response.put("txType", result.txType());
        response.put("txStatus", result.txStatus());
        response.put("txHash", result.txHash());
        return response;
    }
}
