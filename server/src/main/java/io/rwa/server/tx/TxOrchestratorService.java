package io.rwa.server.tx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.server.common.ApiException;
import io.rwa.server.web3.SendRawResult;
import io.rwa.server.web3.Web3FunctionService;
import java.math.BigInteger;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;

@Service
public class TxOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(TxOrchestratorService.class);
    private static final int MAX_SEND_ATTEMPTS = 2;

    private final OutboxTxRepository outboxTxRepository;
    private final NonceLockManager nonceLockManager;
    private final Web3FunctionService web3FunctionService;
    private final GasManagerService gasManagerService;
    private final ObjectMapper objectMapper;

    public TxOrchestratorService(
        OutboxTxRepository outboxTxRepository,
        NonceLockManager nonceLockManager,
        Web3FunctionService web3FunctionService,
        GasManagerService gasManagerService,
        ObjectMapper objectMapper
    ) {
        this.outboxTxRepository = outboxTxRepository;
        this.nonceLockManager = nonceLockManager;
        this.web3FunctionService = web3FunctionService;
        this.gasManagerService = gasManagerService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public OutboxTxEntity submitContractTx(
        String requestId,
        String fromAddress,
        String privateKeyHex,
        String toAddress,
        Function function,
        String txType,
        JsonNode payload
    ) {
        Optional<OutboxTxEntity> existing = outboxTxRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return existing.get();
        }

        OutboxTxEntity tx = new OutboxTxEntity();
        tx.setOutboxId(UUID.randomUUID());
        tx.setRequestId(requestId);
        tx.setFromAddress(fromAddress.toLowerCase());
        tx.setToAddress(toAddress == null ? null : toAddress.toLowerCase());
        tx.setStatus(OutboxTxStatus.CREATED.name());
        tx.setTxType(txType);
        tx.setPayload(payload == null ? null : payload.toString());
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        outboxTxRepository.save(tx);

        return nonceLockManager.withAddressLock(fromAddress, () -> {
            GasPreflightResult latestPreflight = null;
            try {
                BigInteger nonce = web3FunctionService.getPendingNonce(fromAddress);
                String functionData = FunctionEncoder.encode(function);
                GasTxRequest gasTxRequest = new GasTxRequest(toAddress, functionData, BigInteger.ZERO);

                for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
                    latestPreflight = gasManagerService.ensureSufficientGasForTx(fromAddress, nonce, gasTxRequest);

                    tx.setNonce(nonce.longValue());
                    tx.setRawTx(web3FunctionService.signFunctionTransactionEip1559(
                        privateKeyHex,
                        toAddress,
                        function,
                        nonce,
                        latestPreflight.estimates().gasLimit(),
                        latestPreflight.estimates().priorityFee(),
                        latestPreflight.estimates().maxFeePerGas()
                    ));
                    tx.setStatus(OutboxTxStatus.SIGNED.name());
                    tx.setUpdatedAt(Instant.now());
                    outboxTxRepository.save(tx);

                    try {
                        SendRawResult sendResult = web3FunctionService.sendSignedRawTransaction(tx.getRawTx());
                        tx.setTxHash(sendResult.txHash());
                        tx.setStatus(OutboxTxStatus.SENT.name());
                        tx.setUpdatedAt(Instant.now());
                        outboxTxRepository.save(tx);
                        return tx;
                    } catch (ApiException e) {
                        if (isInsufficientFundsError(e) && attempt < MAX_SEND_ATTEMPTS) {
                            log.warn(
                                "insufficient funds on sendRaw, retry with auto top-up. from={} requestId={} attempt={}",
                                fromAddress,
                                requestId,
                                attempt
                            );
                            continue;
                        }
                        throw e;
                    }
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to submit tx after retries");
            } catch (Exception e) {
                String reasonCode = resolveFailureReasonCode(e);
                GasPreflightResult failureSnapshot = latestPreflight;
                if (e instanceof GasSponsorshipException gasError && gasError.getPreflightResult() != null) {
                    failureSnapshot = gasError.getPreflightResult();
                }

                tx.setStatus(OutboxTxStatus.FAILED.name());
                tx.setLastError(buildFailureErrorPayload(reasonCode, e.getMessage(), failureSnapshot));
                tx.setUpdatedAt(Instant.now());
                outboxTxRepository.save(tx);
                if (e instanceof ApiException apiException) {
                    throw apiException;
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to submit tx: " + e.getMessage());
            }
        });
    }

    public Optional<OutboxTxEntity> findByOutboxId(UUID outboxId) {
        return outboxTxRepository.findById(outboxId);
    }

    public Optional<OutboxTxEntity> findByHash(String txHash) {
        return outboxTxRepository.findByTxHash(txHash);
    }

    private boolean isInsufficientFundsError(Exception e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("insufficient funds");
    }

    private String resolveFailureReasonCode(Exception e) {
        if (e instanceof GasSponsorshipException gasError) {
            return gasError.getReasonCode();
        }
        if (isInsufficientFundsError(e)) {
            return GasManagerService.REASON_INSUFFICIENT_USER_GAS;
        }
        return "TOPUP_TX_FAILED";
    }

    private String buildFailureErrorPayload(String reasonCode, String message, GasPreflightResult preflight) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("failureReasonCode", reasonCode);
            root.put("message", message == null ? "" : message);

            if (preflight != null) {
                ObjectNode snapshot = root.putObject("preflightSnapshot");
                snapshot.put("requiredUpperWei", toStringValue(preflight.requiredUpperWei()));
                snapshot.put("balanceWei", toStringValue(preflight.currentBalanceWei()));
                snapshot.put("toppedUp", preflight.toppedUp());
                snapshot.put("topUpTxHash", preflight.topUpTxHash());
                if (preflight.estimates() != null) {
                    ObjectNode estimates = snapshot.putObject("estimates");
                    estimates.put("nonce", toStringValue(preflight.estimates().nonce()));
                    estimates.put("gasEstimate", toStringValue(preflight.estimates().gasEstimate()));
                    estimates.put("gasLimit", toStringValue(preflight.estimates().gasLimit()));
                    estimates.put("baseFeePerGas", toStringValue(preflight.estimates().baseFeePerGas()));
                    estimates.put("maxFeePerGas", toStringValue(preflight.estimates().maxFeePerGas()));
                    estimates.put("priorityFee", toStringValue(preflight.estimates().priorityFee()));
                    estimates.put("l1FeeWei", toStringValue(preflight.estimates().l1FeeWei()));
                }
            }
            return root.toString();
        } catch (Exception ignored) {
            return "{\"failureReasonCode\":\"" + reasonCode + "\",\"message\":\"" + (message == null ? "" : message) + "\"}";
        }
    }

    private String toStringValue(BigInteger value) {
        return value == null ? null : value.toString();
    }

    @Transactional
    public void markMined(UUID outboxId) {
        OutboxTxEntity tx = outboxTxRepository.findById(outboxId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox tx not found"));
        tx.setStatus(OutboxTxStatus.MINED.name());
        tx.setUpdatedAt(Instant.now());
        outboxTxRepository.save(tx);
    }

    @Transactional
    public void markFailed(UUID outboxId, String error) {
        OutboxTxEntity tx = outboxTxRepository.findById(outboxId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox tx not found"));
        tx.setStatus(OutboxTxStatus.FAILED.name());
        tx.setLastError(error);
        tx.setUpdatedAt(Instant.now());
        outboxTxRepository.save(tx);
    }

    public OutboxTxEntity waitForMined(UUID outboxId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            OutboxTxEntity tx = findByOutboxId(outboxId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox tx not found"));
            if (OutboxTxStatus.MINED.name().equals(tx.getStatus())) {
                return tx;
            }
            if (OutboxTxStatus.FAILED.name().equals(tx.getStatus())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Chain tx failed: " + tx.getLastError());
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while waiting tx mining");
            }
        }
        throw new ApiException(HttpStatus.ACCEPTED, "Tx not mined yet");
    }
}
