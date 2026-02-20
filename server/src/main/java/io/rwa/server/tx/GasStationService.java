package io.rwa.server.tx;

import io.rwa.server.config.RwaProperties;
import io.rwa.server.web3.SendRawResult;
import io.rwa.server.web3.Web3FunctionService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

@Service
public class GasStationService {

    public static final String REASON_INSUFFICIENT_GAS_STATION_FUNDS = "INSUFFICIENT_GAS_STATION_FUNDS";
    public static final String REASON_TOPUP_TX_FAILED = "TOPUP_TX_FAILED";

    private static final Logger log = LoggerFactory.getLogger(GasStationService.class);
    private static final BigInteger NATIVE_TRANSFER_GAS_LIMIT = BigInteger.valueOf(21_000L);
    private static final Duration TOPUP_CONFIRM_TIMEOUT = Duration.ofMinutes(3);

    private final Web3FunctionService web3FunctionService;
    private final RwaProperties properties;

    public GasStationService(Web3FunctionService web3FunctionService, RwaProperties properties) {
        this.web3FunctionService = web3FunctionService;
        this.properties = properties;
    }

    public boolean isSponsorEnabled() {
        return properties.getGasSponsor().isEnabled();
    }

    public boolean isConfigured() {
        String privateKey = properties.getGasSponsor().getStationPrivateKey();
        return privateKey != null && !privateKey.isBlank();
    }

    public String stationAddressOrNull() {
        if (!isConfigured()) {
            return null;
        }
        return Credentials.create(properties.getGasSponsor().getStationPrivateKey()).getAddress().toLowerCase();
    }

    public String requireStationAddress() {
        String privateKey = requireStationPrivateKey();
        return Credentials.create(privateKey).getAddress().toLowerCase();
    }

    public TopUpResult sendEth(String toAddress, BigInteger amountWei) {
        if (!isSponsorEnabled()) {
            throw new GasSponsorshipException(REASON_TOPUP_TX_FAILED, "Gas sponsor is disabled");
        }

        String privateKey = requireStationPrivateKey();
        String stationAddress = Credentials.create(privateKey).getAddress().toLowerCase();
        String normalizedTo = toAddress.toLowerCase();

        try {
            BigInteger nonce = web3FunctionService.getPendingNonce(stationAddress);
            BigInteger priorityFee = resolvePriorityFee();
            BigInteger baseFee = resolveBaseFee();
            BigInteger maxFeePerGas = ceilMultiply(baseFee, properties.getGasSponsor().getMaxFeeBaseMultiplier())
                .add(priorityFee);

            BigInteger stationBalance = web3FunctionService.getPendingBalance(stationAddress);
            BigInteger requiredBalance = amountWei.add(NATIVE_TRANSFER_GAS_LIMIT.multiply(maxFeePerGas));
            if (stationBalance.compareTo(requiredBalance) < 0) {
                throw new GasSponsorshipException(
                    REASON_INSUFFICIENT_GAS_STATION_FUNDS,
                    "Gas station balance is insufficient: balance="
                        + stationBalance
                        + ", required="
                        + requiredBalance
                );
            }

            String rawTx = web3FunctionService.signEip1559Transaction(
                privateKey,
                normalizedTo,
                "0x",
                amountWei,
                nonce,
                NATIVE_TRANSFER_GAS_LIMIT,
                priorityFee,
                maxFeePerGas
            );
            SendRawResult sent = web3FunctionService.sendSignedRawTransaction(rawTx);
            waitForTopUpMined(sent.txHash());

            log.info("GasTopUp to={} amountWei={} txHash={}", normalizedTo, amountWei, sent.txHash());
            warnIfStationBalanceLow(stationAddress);
            return new TopUpResult(sent.txHash(), amountWei);
        } catch (GasSponsorshipException e) {
            throw e;
        } catch (Exception e) {
            if (containsInsufficientFunds(e.getMessage())) {
                throw new GasSponsorshipException(REASON_INSUFFICIENT_GAS_STATION_FUNDS, e.getMessage(), null, e);
            }
            throw new GasSponsorshipException(REASON_TOPUP_TX_FAILED, "Top-up tx failed: " + e.getMessage(), null, e);
        }
    }

    private void waitForTopUpMined(String txHash) {
        long requiredConfirmations = Math.max(1L, properties.getGasSponsor().getTopupConfirmations());
        Instant deadline = Instant.now().plus(TOPUP_CONFIRM_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            var receiptOpt = web3FunctionService.getReceipt(txHash);
            if (receiptOpt.isPresent()) {
                var receipt = receiptOpt.get();
                boolean success = "0x1".equalsIgnoreCase(receipt.getStatus()) || receipt.isStatusOK();
                if (!success) {
                    throw new GasSponsorshipException(REASON_TOPUP_TX_FAILED, "Top-up tx reverted: " + txHash);
                }

                BigInteger latestBlock = web3FunctionService.latestBlockNumber();
                BigInteger confirmations = latestBlock.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
                if (confirmations.compareTo(BigInteger.valueOf(requiredConfirmations)) >= 0) {
                    return;
                }
            }
            sleepUnchecked(1000L);
        }

        throw new GasSponsorshipException(
            REASON_TOPUP_TX_FAILED,
            "Top-up tx confirmation timeout: txHash=" + txHash
        );
    }

    private void warnIfStationBalanceLow(String stationAddress) {
        BigInteger remaining = web3FunctionService.getPendingBalance(stationAddress);
        BigInteger threshold = properties.getGasSponsor().getStationTopupTargetWei();
        if (remaining.compareTo(threshold) < 0) {
            log.warn("GasStationBalanceLow address={} balanceWei={} thresholdWei={}", stationAddress, remaining, threshold);
        }
    }

    private BigInteger resolvePriorityFee() {
        try {
            return web3FunctionService.getMaxPriorityFeePerGas();
        } catch (Exception ignored) {
            return properties.getGasSponsor().getMaxPriorityFeeWei();
        }
    }

    private BigInteger resolveBaseFee() {
        try {
            return web3FunctionService.getLatestBaseFeePerGas();
        } catch (Exception e) {
            log.warn("Failed to read base fee. Fallback to eth_gasPrice: {}", e.getMessage());
            return web3FunctionService.getGasPrice();
        }
    }

    private String requireStationPrivateKey() {
        String key = properties.getGasSponsor().getStationPrivateKey();
        if (key == null || key.isBlank()) {
            throw new GasSponsorshipException(
                REASON_TOPUP_TX_FAILED,
                "GAS_STATION_PRIVATE_KEY is required when GAS_SPONSOR_ENABLED=true"
            );
        }
        return key;
    }

    private boolean containsInsufficientFunds(String message) {
        if (message == null) {
            return false;
        }
        return message.toLowerCase().contains("insufficient funds");
    }

    private BigInteger ceilMultiply(BigInteger value, double multiplier) {
        return BigDecimal.valueOf(multiplier)
            .multiply(new BigDecimal(value))
            .setScale(0, RoundingMode.CEILING)
            .toBigInteger();
    }

    private void sleepUnchecked(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GasSponsorshipException(REASON_TOPUP_TX_FAILED, "Interrupted while waiting top-up receipt", null, e);
        }
    }

    public record TopUpResult(
        String txHash,
        BigInteger amountWei
    ) {
    }
}
