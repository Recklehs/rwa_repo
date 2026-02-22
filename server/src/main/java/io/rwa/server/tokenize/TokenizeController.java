package io.rwa.server.tokenize;

import io.rwa.server.tokenize.TokenizeService.TokenizeResult;
import io.rwa.server.web3.ContractGatewayService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/classes")
public class TokenizeController {

    private final TokenizeService tokenizeService;
    private final ContractGatewayService contractGatewayService;

    public TokenizeController(TokenizeService tokenizeService, ContractGatewayService contractGatewayService) {
        this.tokenizeService = tokenizeService;
        this.contractGatewayService = contractGatewayService;
    }

    @PostMapping("/{classId}/tokenize")
    public Map<String, Object> tokenize(
        @PathVariable String classId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody(required = false) TokenizeRequest request
    ) {
        TokenizeResult result = tokenizeService.tokenizeClass(classId, request, idempotencyKey);
        return Map.of(
            "classId", result.classId(),
            "docHash", result.docHash(),
            "status", result.status(),
            "outboxIds", result.outboxIds()
        );
    }

    @GetMapping("/{classId}/registry")
    public Map<String, Object> registry(@PathVariable String classId) {
        ContractGatewayService.RegistryClassInfo info = contractGatewayService.getRegistryClass(classId);
        boolean registered = info.unitCount() > 0;
        return Map.of(
            "classId", classId,
            "registered", registered,
            "docHash", info.docHash(),
            "unitCount", info.unitCount(),
            "baseTokenId", info.baseTokenId(),
            "status", info.status(),
            "issuedAt", info.issuedAt()
        );
    }
}
