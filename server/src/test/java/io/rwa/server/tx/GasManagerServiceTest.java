package io.rwa.server.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import io.rwa.server.config.RwaProperties;
import io.rwa.server.web3.Web3FunctionService;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.core.methods.request.Transaction;

@ExtendWith(MockitoExtension.class)
class GasManagerServiceTest {

    @Mock
    private Web3FunctionService web3FunctionService;

    @Mock
    private GasStationService gasStationService;

    @Mock
    private NonceLockManager nonceLockManager;

    private RwaProperties properties;
    private GasManagerService gasManagerService;

    @BeforeEach
    void setUp() {
        properties = new RwaProperties();
        properties.setGiwaChainId(91342L);
        properties.getGasSponsor().setEnabled(true);
        properties.getGasSponsor().setStationPrivateKey(
            "0x0123456789012345678901234567890123456789012345678901234567890123"
        );
        properties.getGasSponsor().setStationInitialGrantWei(BigInteger.valueOf(1_000L));
        properties.getGasSponsor().setStationTopupTargetWei(BigInteger.valueOf(500_000L));
        properties.getGasSponsor().setStationDustReserveWei(BigInteger.valueOf(30L));
        properties.getGasSponsor().setEstimateMultiplier(1.2d);
        properties.getGasSponsor().setMaxFeeBaseMultiplier(2.0d);
        properties.getGasSponsor().setMaxPriorityFeeWei(BigInteger.TEN);
        properties.getGasSponsor().setL1FeeOracleAddress("");
        properties.getGasSponsor().setL1FeeMultiplier(1.2d);

        lenient().when(nonceLockManager.withAddressLock(eq("0xuser"), any())).thenAnswer(invocation -> {
            NonceLockManager.LockedCallback<?> callback = invocation.getArgument(1);
            return callback.run();
        });
        lenient().when(gasStationService.requireStationAddress()).thenReturn("0xstation");

        gasManagerService = new GasManagerService(
            web3FunctionService,
            gasStationService,
            nonceLockManager,
            properties
        );
    }

    @Test
    @DisplayName("ensureInitialGasGranted는 잔액이 충분하면 top-up을 호출하지 않는다")
    void ensureInitialGasGrantedShouldSkipTopUpWhenBalanceIsEnough() {
        when(web3FunctionService.getPendingBalance("0xuser")).thenReturn(BigInteger.valueOf(1_000L));

        gasManagerService.ensureInitialGasGranted("0xuser");

        verify(gasStationService, never()).sendEth(any(), any());
    }

    @Test
    @DisplayName("ensureInitialGasGranted는 target-balance 만큼만 top-up을 전송한다")
    void ensureInitialGasGrantedShouldTopUpDeltaAmount() {
        when(web3FunctionService.getPendingBalance("0xuser")).thenReturn(BigInteger.valueOf(600L));
        when(gasStationService.sendEth("0xuser", BigInteger.valueOf(400L)))
            .thenReturn(new GasStationService.TopUpResult("0xtopup", BigInteger.valueOf(400L)));

        gasManagerService.ensureInitialGasGranted("0xuser");

        verify(gasStationService).sendEth("0xuser", BigInteger.valueOf(400L));
    }

    @Test
    @DisplayName("ensureSufficientGasForTx는 부족 시 top-up 후 정상 결과를 반환한다")
    void ensureSufficientGasForTxShouldTopUpWhenInsufficient() {
        when(gasStationService.stationAddressOrNull()).thenReturn("0xstation");
        when(web3FunctionService.getMaxPriorityFeePerGas()).thenReturn(BigInteger.TEN);
        when(web3FunctionService.getLatestBaseFeePerGas()).thenReturn(BigInteger.valueOf(100L));
        when(web3FunctionService.estimateGas(any(Transaction.class))).thenReturn(BigInteger.valueOf(1_000L));
        when(web3FunctionService.getPendingBalance("0xuser"))
            .thenReturn(BigInteger.valueOf(100_000L), BigInteger.valueOf(500_000L));
        when(gasStationService.sendEth("0xuser", BigInteger.valueOf(400_000L)))
            .thenReturn(new GasStationService.TopUpResult("0xtopup", BigInteger.valueOf(400_000L)));

        GasPreflightResult result = gasManagerService.ensureSufficientGasForTx(
            "0xuser",
            BigInteger.ONE,
            new GasTxRequest("0xmarket", "0x1234", BigInteger.ZERO)
        );

        assertThat(result.estimates().gasLimit()).isEqualTo(BigInteger.valueOf(1_200L));
        assertThat(result.requiredUpperWei()).isEqualTo(BigInteger.valueOf(252_030L));
        assertThat(result.toppedUp()).isTrue();
        assertThat(result.topUpTxHash()).isEqualTo("0xtopup");
        verify(gasStationService).sendEth("0xuser", BigInteger.valueOf(400_000L));
    }

    @Test
    @DisplayName("L1 oracle 조회가 실패해도 fail-soft로 l1Fee=0 처리하고 진행한다")
    void ensureSufficientGasForTxShouldFailSoftWhenL1OracleFails() {
        properties.getGasSponsor().setL1FeeOracleAddress("0x420000000000000000000000000000000000000F");

        when(gasStationService.stationAddressOrNull()).thenReturn("0xstation");
        when(web3FunctionService.getMaxPriorityFeePerGas()).thenReturn(BigInteger.TEN);
        when(web3FunctionService.getLatestBaseFeePerGas()).thenReturn(BigInteger.valueOf(100L));
        when(web3FunctionService.estimateGas(any(Transaction.class))).thenReturn(BigInteger.valueOf(1_000L));
        when(web3FunctionService.getPendingBalance("0xuser")).thenReturn(BigInteger.valueOf(1_000_000L));
        when(web3FunctionService.callFunction(eq("0x420000000000000000000000000000000000000F"), any()))
            .thenThrow(new RuntimeException("oracle error"));

        GasPreflightResult result = gasManagerService.ensureSufficientGasForTx(
            "0xuser",
            BigInteger.ONE,
            new GasTxRequest("0xmarket", "0x1234", BigInteger.ZERO)
        );

        assertThat(result.estimates().l1FeeWei()).isEqualTo(BigInteger.ZERO);
        assertThat(result.toppedUp()).isFalse();
        verify(gasStationService, never()).sendEth(any(), any());
    }
}
