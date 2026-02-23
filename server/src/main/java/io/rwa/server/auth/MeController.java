package io.rwa.server.auth;

import io.rwa.server.query.ReadModelQueryService;
import io.rwa.server.security.UserPrincipal;
import io.rwa.server.security.UserPrincipalContext;
import io.rwa.server.trade.UserOrderService;
import io.rwa.server.wallet.UserEntity;
import io.rwa.server.wallet.WalletEntity;
import io.rwa.server.wallet.WalletService;
import io.rwa.server.web3.ContractGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
public class MeController {

    private final WalletService walletService;
    private final ContractGatewayService contractGatewayService;
    private final UserOrderService userOrderService;
    private final ReadModelQueryService readModelQueryService;

    public MeController(
        WalletService walletService,
        ContractGatewayService contractGatewayService,
        UserOrderService userOrderService,
        ReadModelQueryService readModelQueryService
    ) {
        this.walletService = walletService;
        this.contractGatewayService = contractGatewayService;
        this.userOrderService = userOrderService;
        this.readModelQueryService = readModelQueryService;
    }

    @GetMapping
    public Map<String, Object> me(HttpServletRequest request) {
        UserPrincipal principal = UserPrincipalContext.require(request);
        UserEntity user = walletService.getUser(principal.userId());
        WalletEntity wallet = walletService.getWalletOrProvisioned(principal.userId());

        List<Map<String, Object>> externalLinks = walletService.listExternalLinks(principal.userId()).stream()
            .map(link -> Map.<String, Object>of(
                "provider", link.provider(),
                "externalUserId", link.externalUserId()
            ))
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", user.getUserId());
        response.put("address", wallet.getAddress());
        response.put("complianceStatus", user.getComplianceStatus().name());
        response.put("externalLinks", externalLinks);
        return response;
    }

    @GetMapping("/wallet")
    public Map<String, Object> wallet(HttpServletRequest request) {
        UserPrincipal principal = UserPrincipalContext.require(request);
        UserEntity user = walletService.getUser(principal.userId());
        WalletEntity wallet = walletService.getWalletOrProvisioned(principal.userId());

        String address = wallet.getAddress();
        String marketAddress = contractGatewayService.marketAddress();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("address", address);
        response.put("ethBalance", contractGatewayService.nativeBalance(address));
        response.put("musdBalance", contractGatewayService.mockUsdBalance(address));
        response.put("shareApprovalForMarket", contractGatewayService.isApprovedForAll(address, marketAddress));
        response.put("musdAllowanceToMarket", contractGatewayService.allowance(address, marketAddress));
        response.put("complianceStatus", user.getComplianceStatus().name());
        return response;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(
        HttpServletRequest request,
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        UserPrincipal principal = UserPrincipalContext.require(request);
        return userOrderService.findBySeller(principal.userId(), limit);
    }

    @GetMapping("/trades")
    public List<Map<String, Object>> trades(
        HttpServletRequest request,
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        UserPrincipal principal = UserPrincipalContext.require(request);
        String address = walletService.getWalletOrProvisioned(principal.userId()).getAddress();
        return readModelQueryService.tradesByAddress(address, limit);
    }
}
