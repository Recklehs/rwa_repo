package io.rwa.server.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.common.ApiException;
import io.rwa.server.tx.OutboxTxEntity;
import io.rwa.server.tx.TxOrchestratorService;
import io.rwa.server.web3.ContractGatewayService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletTransferService {

    private static final int MUSD_DECIMALS = 18;

    private final WalletService walletService;
    private final ContractGatewayService contractGatewayService;
    private final TxOrchestratorService txOrchestratorService;
    private final ObjectMapper objectMapper;

    public WalletTransferService(
        WalletService walletService,
        ContractGatewayService contractGatewayService,
        TxOrchestratorService txOrchestratorService,
        ObjectMapper objectMapper
    ) {
        this.walletService = walletService;
        this.contractGatewayService = contractGatewayService;
        this.txOrchestratorService = txOrchestratorService;
        this.objectMapper = objectMapper;
    }

    public WalletTransferResult transferMockUsd(UUID senderUserId, WalletTransferRequest request, String idempotencyKey) {
        walletService.assertApproved(senderUserId);

        WalletService.ExternalUserWalletView recipient = resolveRecipient(senderUserId, request);
        walletService.assertApproved(recipient.userId());

        String senderAddress = walletService.getWalletOrProvisioned(senderUserId).getAddress();
        String senderPrivateKey = walletService.decryptUserPrivateKey(senderUserId);
        BigInteger amount = resolveAmount(request.amount(), request.amountHuman());

        OutboxTxEntity tx = txOrchestratorService.submitContractTx(
            idempotencyKey + ":wallet:transfer:musd:" + senderUserId + ":" + recipient.userId(),
            senderAddress,
            senderPrivateKey,
            contractGatewayService.mockUsdAddress(),
            contractGatewayService.fnMockUsdTransfer(recipient.address(), amount),
            "WALLET_TRANSFER_MUSD",
            objectMapper.createObjectNode()
                .put("senderUserId", senderUserId.toString())
                .put("senderAddress", senderAddress)
                .put("recipientUserId", recipient.userId().toString())
                .put("recipientAddress", recipient.address())
                .put("amount", amount.toString())
        );

        return new WalletTransferResult(tx.getOutboxId(), tx.getTxType(), tx.getStatus(), tx.getTxHash());
    }

    private WalletService.ExternalUserWalletView resolveRecipient(UUID senderUserId, WalletTransferRequest request) {
        UUID recipientUserId = request.recipientUserId();
        String recipientExternalUserId = request.recipientExternalUserId();

        if (recipientUserId != null && recipientExternalUserId != null && !recipientExternalUserId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Use either recipientUserId or recipientExternalUserId");
        }

        if (recipientUserId != null) {
            WalletEntity wallet = walletService.getWalletOrProvisioned(recipientUserId);
            if (senderUserId.equals(recipientUserId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "self-transfer is not allowed");
            }
            UserEntity user = walletService.getUser(recipientUserId);
            return new WalletService.ExternalUserWalletView(
                recipientUserId,
                wallet.getAddress(),
                null,
                null,
                user.getComplianceStatus(),
                user.getComplianceUpdatedAt()
            );
        }

        if (recipientExternalUserId == null || recipientExternalUserId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "recipientUserId or recipientExternalUserId is required");
        }

        String provider = request.recipientProvider();
        WalletService.ExternalUserWalletView recipient = walletService.findByExternalUser(provider, recipientExternalUserId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Recipient user not found"));

        if (senderUserId.equals(recipient.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self-transfer is not allowed");
        }
        return recipient;
    }

    private BigInteger resolveAmount(BigInteger amount, BigDecimal amountHuman) {
        if (amount != null && amountHuman != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide either amount(raw) or amountHuman, not both");
        }

        if (amountHuman != null) {
            return toRawFromHuman(amountHuman);
        }

        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "amount or amountHuman must be greater than 0");
        }

        return amount;
    }

    private BigInteger toRawFromHuman(BigDecimal amountHuman) {
        if (amountHuman.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "amountHuman must be greater than 0");
        }
        try {
            return amountHuman.movePointRight(MUSD_DECIMALS).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "amountHuman supports up to 18 decimal places");
        }
    }
}
