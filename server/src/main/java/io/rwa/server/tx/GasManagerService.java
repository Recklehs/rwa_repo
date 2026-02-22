package io.rwa.server.tx;

import io.rwa.server.config.RwaProperties;
import io.rwa.server.web3.Web3FunctionService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.core.methods.request.Transaction;

@Service
public class GasManagerService {

    public static final String REASON_INSUFFICIENT_USER_GAS = "INSUFFICIENT_USER_GAS";

    private static final Logger log = LoggerFactory.getLogger(GasManagerService.class);

    private final Web3FunctionService web3FunctionService;
    private final GasStationService gasStationService;
    private final NonceLockManager nonceLockManager;
    private final RwaProperties properties;

    public GasManagerService(
        Web3FunctionService web3FunctionService,
        GasStationService gasStationService,
        NonceLockManager nonceLockManager,
        RwaProperties properties
    ) {
        this.web3FunctionService = web3FunctionService;
        this.gasStationService = gasStationService;
        this.nonceLockManager = nonceLockManager;
        this.properties = properties;
    }

    public void ensureInitialGasGranted(String address) {
        if (!properties.getGasSponsor().isEnabled()) {
            return;
        }
        String normalizedAddress = address.toLowerCase();
        String stationAddress = gasStationService.requireStationAddress();
        nonceLockManager.withAddressLock(normalizedAddress, () -> {
            if (stationAddress.equalsIgnoreCase(normalizedAddress)) {
                return null;
            }

            BigInteger balance = web3FunctionService.getPendingBalance(normalizedAddress);
            BigInteger target = properties.getGasSponsor().getStationInitialGrantWei();
            if (balance.compareTo(target) >= 0) {
                return null;
            }

            BigInteger topUpAmount = target.subtract(balance);
            gasStationService.sendEth(normalizedAddress, topUpAmount);
            return null;
        });
    }

    public GasPreflightResult ensureSufficientGasForTx(String fromAddress, BigInteger nonce, GasTxRequest txRequest) {
        String normalizedFrom = fromAddress.toLowerCase();

        BigInteger priorityFee = resolvePriorityFee();
        BigInteger baseFee = resolveBaseFee();
        BigInteger maxFeePerGas = ceilMultiply(baseFee, properties.getGasSponsor().getMaxFeeBaseMultiplier())
            .add(priorityFee);

        BigInteger gasEstimate = estimateGasWithFallback(normalizedFrom, nonce, txRequest, priorityFee, maxFeePerGas);
        BigInteger gasLimit = ceilMultiply(gasEstimate, properties.getGasSponsor().getEstimateMultiplier());
        BigInteger l1FeeWei = estimateL1FeeOrZero(nonce, txRequest, gasLimit, priorityFee, maxFeePerGas);
        BigInteger requiredUpperWei = gasLimit.multiply(maxFeePerGas)
            .add(l1FeeWei)
            .add(properties.getGasSponsor().getStationDustReserveWei());
        BigInteger currentBalance = web3FunctionService.getPendingBalance(normalizedFrom);

        GasPreflightEstimates estimates = new GasPreflightEstimates(
            nonce,
            gasEstimate,
            gasLimit,
            baseFee,
            maxFeePerGas,
            priorityFee,
            l1FeeWei
        );
        GasPreflightResult preflight = new GasPreflightResult(requiredUpperWei, currentBalance, false, null, estimates);

        String stationAddress = gasStationService.stationAddressOrNull();
        boolean skipTopUp = stationAddress != null && stationAddress.equalsIgnoreCase(normalizedFrom);
        if (skipTopUp || currentBalance.compareTo(requiredUpperWei) >= 0) {
            logPreflight(normalizedFrom, preflight);
            return preflight;
        }

        if (!properties.getGasSponsor().isEnabled()) {
            throw new GasSponsorshipException(
                REASON_INSUFFICIENT_USER_GAS,
                "Insufficient gas and sponsor is disabled",
                preflight,
                null
            );
        }

        BigInteger targetBalance = properties.getGasSponsor().getStationTopupTargetWei().max(requiredUpperWei);
        BigInteger topUpAmount = targetBalance.subtract(currentBalance);
        try {
            GasStationService.TopUpResult topUpResult = gasStationService.sendEth(normalizedFrom, topUpAmount);
            BigInteger recheckedBalance = web3FunctionService.getPendingBalance(normalizedFrom);

            GasPreflightResult toppedUpResult = new GasPreflightResult(
                requiredUpperWei,
                recheckedBalance,
                true,
                topUpResult.txHash(),
                estimates
            );
            logPreflight(normalizedFrom, toppedUpResult);

            if (recheckedBalance.compareTo(requiredUpperWei) < 0) {
                throw new GasSponsorshipException(
                    REASON_INSUFFICIENT_USER_GAS,
                    "Balance is still insufficient after top-up",
                    toppedUpResult,
                    null
                );
            }
            return toppedUpResult;
        } catch (GasSponsorshipException e) {
            throw new GasSponsorshipException(e.getReasonCode(), e.getMessage(), preflight, e);
        }
    }

    private BigInteger estimateL1FeeOrZero(
        BigInteger nonce,
        GasTxRequest txRequest,
        BigInteger gasLimit,
        BigInteger priorityFee,
        BigInteger maxFeePerGas
    ) {
        String oracleAddress = properties.getGasSponsor().getL1FeeOracleAddress();
        if (oracleAddress == null || oracleAddress.isBlank()) {
            return BigInteger.ZERO;
        }

        try {
            RawTransaction unsigned = RawTransaction.createTransaction(
                properties.getGiwaChainId(),
                nonce,
                gasLimit,
                txRequest.toAddress(),
                txRequest.value(),
                txRequest.data(),
                priorityFee,
                maxFeePerGas
            );
            byte[] txBytes = TransactionEncoder.encode(unsigned);
            Function function = new Function(
                "getL1Fee",
                List.of(new DynamicBytes(txBytes)),
                List.of(new TypeReference<Uint256>() {
                })
            );

            List<Type> output = web3FunctionService.callFunction(oracleAddress, function);
            Uint256 fee = web3FunctionService.requireTyped(output, 0, Uint256.class);
            return ceilMultiply(fee.getValue(), properties.getGasSponsor().getL1FeeMultiplier());
        } catch (Exception e) {
            log.warn("L1 fee estimate failed. Continue with 0 (fail-soft): {}", e.getMessage());
            return BigInteger.ZERO;
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

    private BigInteger estimateGasWithFallback(
        String fromAddress,
        BigInteger nonce,
        GasTxRequest txRequest,
        BigInteger priorityFee,
        BigInteger maxFeePerGas
    ) {
        try {
            return web3FunctionService.estimateGas(new Transaction(
                fromAddress,
                nonce,
                null,
                null,
                txRequest.toAddress(),
                txRequest.value(),
                txRequest.data(),
                properties.getGiwaChainId(),
                priorityFee,
                maxFeePerGas
            ));
        } catch (Exception e) {
            if (containsInsufficientFunds(e.getMessage())) {
                BigInteger fallback = BigInteger.valueOf(properties.getTx().getDefaultGasLimit());
                log.warn("estimateGas failed with insufficient funds. Fallback gasEstimate={}", fallback);
                return fallback;
            }
            throw e;
        }
    }

    private BigInteger ceilMultiply(BigInteger value, double multiplier) {
        return BigDecimal.valueOf(multiplier)
            .multiply(new BigDecimal(value))
            .setScale(0, RoundingMode.CEILING)
            .toBigInteger();
    }

    private void logPreflight(String fromAddress, GasPreflightResult result) {
        log.info(
            "GasPreflight from={} requiredUpperWei={} balanceWei={} toppedUp={} topUpTxHash={}",
            fromAddress,
            result.requiredUpperWei(),
            result.currentBalanceWei(),
            result.toppedUp(),
            result.topUpTxHash()
        );
    }

    private boolean containsInsufficientFunds(String message) {
        if (message == null) {
            return false;
        }
        return message.toLowerCase().contains("insufficient funds");
    }
}
