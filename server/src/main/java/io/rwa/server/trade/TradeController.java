package io.rwa.server.trade;

import io.rwa.server.security.UserPrincipal;
import io.rwa.server.security.UserPrincipalContext;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trade")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping("/list")
    public Map<String, Object> list(
        @Valid @RequestBody TradeListRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpServletRequest
    ) {
        UserPrincipal principal = UserPrincipalContext.require(httpServletRequest);
        TradeResult result = tradeService.list(request, principal.userId(), idempotencyKey);
        return Map.of(
            "outboxIds", result.outboxIds(),
            "tokenId", result.tokenId(),
            "amount", result.amount(),
            "unitPrice", result.unitPrice()
        );
    }

    @PostMapping("/buy")
    public Map<String, Object> buy(
        @Valid @RequestBody TradeBuyRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpServletRequest
    ) {
        UserPrincipal principal = UserPrincipalContext.require(httpServletRequest);
        TradeResult result = tradeService.buy(request, principal.userId(), idempotencyKey);
        return Map.of(
            "outboxIds", result.outboxIds(),
            "listingId", result.listingId(),
            "tokenId", result.tokenId(),
            "amount", result.amount(),
            "unitPrice", result.unitPrice(),
            "cost", result.cost()
        );
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(
        @Valid @RequestBody TradeCancelRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpServletRequest
    ) {
        UserPrincipal principal = UserPrincipalContext.require(httpServletRequest);
        TradeCancelResult result = tradeService.cancel(request, principal.userId(), idempotencyKey);
        return Map.of(
            "outboxId", result.outboxId(),
            "listingId", result.listingId()
        );
    }
}
