package io.rwa.server.web3;

import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

@Service
public class Web3FunctionService {

    private final Web3j web3j;
    private final RwaProperties properties;

    public Web3FunctionService(Web3j web3j, RwaProperties properties) {
        this.web3j = web3j;
        this.properties = properties;
    }

    public BigInteger getPendingNonce(String fromAddress) {
        try {
            EthGetTransactionCount count = web3j.ethGetTransactionCount(
                fromAddress,
                DefaultBlockParameterName.PENDING
            ).send();
            return count.getTransactionCount();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to read pending nonce: " + e.getMessage());
        }
    }

    public BigInteger getBalance(String address, DefaultBlockParameterName blockParameterName) {
        try {
            EthGetBalance balance = web3j.ethGetBalance(address, blockParameterName).send();
            return balance.getBalance();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to read balance: " + e.getMessage());
        }
    }

    public BigInteger getPendingBalance(String address) {
        return getBalance(address, DefaultBlockParameterName.PENDING);
    }

    public BigInteger getGasPrice() {
        try {
            EthGasPrice gasPrice = web3j.ethGasPrice().send();
            return gasPrice.getGasPrice();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to read gas price: " + e.getMessage());
        }
    }

    public BigInteger getMaxPriorityFeePerGas() {
        try {
            EthMaxPriorityFeePerGas maxPriority = web3j.ethMaxPriorityFeePerGas().send();
            return maxPriority.getMaxPriorityFeePerGas();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to read max priority fee: " + e.getMessage());
        }
    }

    public BigInteger getLatestBaseFeePerGas() {
        try {
            EthBlock latest = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send();
            EthBlock.Block block = latest.getBlock();
            if (block == null || block.getBaseFeePerGasRaw() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Latest block does not include baseFeePerGas");
            }
            return block.getBaseFeePerGas();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to read latest base fee: " + e.getMessage());
        }
    }

    public BigInteger estimateGas(Transaction request) {
        try {
            EthEstimateGas estimated = web3j.ethEstimateGas(request).send();
            if (estimated.hasError()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "eth_estimateGas failed: " + estimated.getError().getMessage());
            }
            return estimated.getAmountUsed();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to estimate gas: " + e.getMessage());
        }
    }

    public String signEip1559Transaction(
        String privateKeyHex,
        String toAddress,
        String data,
        BigInteger value,
        BigInteger nonce,
        BigInteger gasLimit,
        BigInteger maxPriorityFeePerGas,
        BigInteger maxFeePerGas
    ) {
        try {
            RawTransaction raw = RawTransaction.createTransaction(
                properties.getGiwaChainId(),
                nonce,
                gasLimit,
                toAddress,
                value,
                data == null ? "0x" : data,
                maxPriorityFeePerGas,
                maxFeePerGas
            );
            Credentials credentials = Credentials.create(privateKeyHex);
            byte[] signed = TransactionEncoder.signMessage(raw, credentials);
            return Numeric.toHexString(signed);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to sign raw tx: " + e.getMessage());
        }
    }

    public String signFunctionTransactionEip1559(
        String privateKeyHex,
        String toAddress,
        Function function,
        BigInteger nonce,
        BigInteger gasLimit,
        BigInteger maxPriorityFeePerGas,
        BigInteger maxFeePerGas
    ) {
        String data = FunctionEncoder.encode(function);
        return signEip1559Transaction(
            privateKeyHex,
            toAddress,
            data,
            BigInteger.ZERO,
            nonce,
            gasLimit,
            maxPriorityFeePerGas,
            maxFeePerGas
        );
    }

    public SendRawResult sendSignedRawTransaction(String rawHex) {
        try {
            org.web3j.protocol.core.methods.response.EthSendTransaction send = web3j.ethSendRawTransaction(rawHex).send();
            if (send.hasError()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "RPC send failed: " + send.getError().getMessage());
            }
            return new SendRawResult(rawHex, send.getTransactionHash());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to send raw tx: " + e.getMessage());
        }
    }

    public List<Type> callFunction(String contractAddress, Function function) {
        try {
            String encoded = FunctionEncoder.encode(function);
            Transaction request = Transaction.createEthCallTransaction(null, contractAddress, encoded);
            EthCall call = web3j.ethCall(request, DefaultBlockParameterName.LATEST).send();
            if (call.hasError()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "eth_call failed: " + call.getError().getMessage());
            }
            return FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "eth_call exception: " + e.getMessage());
        }
    }

    public Optional<TransactionReceipt> getReceipt(String txHash) {
        try {
            EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(txHash).send();
            return receipt.getTransactionReceipt();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to get receipt: " + e.getMessage());
        }
    }

    public BigInteger latestBlockNumber() {
        try {
            EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
            return blockNumber.getBlockNumber();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to get latest block: " + e.getMessage());
        }
    }

    public BigInteger estimateCost(BigInteger amount, BigInteger unitPrice, BigInteger shareScale) {
        return amount.multiply(unitPrice).divide(shareScale);
    }

    public <T extends Type> T requireTyped(List<Type> outputs, int index, Class<T> clazz) {
        if (outputs.size() <= index || !clazz.isInstance(outputs.get(index))) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected ABI output at index " + index);
        }
        return clazz.cast(outputs.get(index));
    }

    public static List<TypeReference<?>> outputs(TypeReference<?>... refs) {
        return List.of(refs);
    }
}
