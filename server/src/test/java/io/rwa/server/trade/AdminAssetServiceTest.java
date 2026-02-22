package io.rwa.server.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import io.rwa.server.publicdata.UnitRepository;
import io.rwa.server.tx.OutboxTxEntity;
import io.rwa.server.tx.TxOrchestratorService;
import io.rwa.server.wallet.WalletService;
import io.rwa.server.web3.ContractGatewayService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.web3j.abi.datatypes.Function;

@ExtendWith(MockitoExtension.class)
class AdminAssetServiceTest {

    @Mock
    private WalletService walletService;

    @Mock
    private UnitRepository unitRepository;

    @Mock
    private ContractGatewayService contractGatewayService;

    @Mock
    private TxOrchestratorService txOrchestratorService;

    private AdminAssetService adminAssetService;

    @BeforeEach
    void setUp() {
        RwaProperties properties = new RwaProperties();
        properties.setIssuerPrivateKey("1111111111111111111111111111111111111111111111111111111111111111");
        properties.setTreasuryPrivateKey("2222222222222222222222222222222222222222222222222222222222222222");

        adminAssetService = new AdminAssetService(
            walletService,
            unitRepository,
            contractGatewayService,
            txOrchestratorService,
            properties,
            new ObjectMapper()
        );
    }

    @Test
    @DisplayName("faucet은 amountHuman(사람 단위)을 18 decimals raw로 변환해 mint한다")
    void faucetShouldConvertHumanAmountToRawAmount() {
        UUID toUserId = UUID.randomUUID();
        FaucetRequest request = new FaucetRequest(toUserId, null, new BigDecimal("1000"));

        Function mintFn = mockFunction();
        when(walletService.getAddress(toUserId)).thenReturn("0xbuyer");
        when(contractGatewayService.mockUsdAddress()).thenReturn("0xmockusd");
        when(contractGatewayService.fnMintMockUsd(anyString(), any(BigInteger.class))).thenReturn(mintFn);
        when(txOrchestratorService.submitContractTx(anyString(), anyString(), anyString(), anyString(), eq(mintFn), anyString(), any()))
            .thenReturn(outboxTx("f47f2076-c8c0-4e8c-b587-37f168652fce"));

        AdminAssetResult result = adminAssetService.faucetMockUsd(request, "idem-faucet-human");

        verify(contractGatewayService).fnMintMockUsd("0xbuyer", new BigInteger("1000000000000000000000"));
        assertThat(result.outboxId()).isEqualTo(UUID.fromString("f47f2076-c8c0-4e8c-b587-37f168652fce"));
    }

    @Test
    @DisplayName("faucet은 기존 amount(raw) 요청도 그대로 처리한다")
    void faucetShouldKeepRawAmountCompatibility() {
        UUID toUserId = UUID.randomUUID();
        FaucetRequest request = new FaucetRequest(toUserId, new BigInteger("123456"), null);

        Function mintFn = mockFunction();
        when(walletService.getAddress(toUserId)).thenReturn("0xbuyer");
        when(contractGatewayService.mockUsdAddress()).thenReturn("0xmockusd");
        when(contractGatewayService.fnMintMockUsd(anyString(), any(BigInteger.class))).thenReturn(mintFn);
        when(txOrchestratorService.submitContractTx(anyString(), anyString(), anyString(), anyString(), eq(mintFn), anyString(), any()))
            .thenReturn(outboxTx("3d5f9655-8be5-48f9-b9ac-018eca9309dd"));

        adminAssetService.faucetMockUsd(request, "idem-faucet-raw");

        verify(contractGatewayService).fnMintMockUsd("0xbuyer", new BigInteger("123456"));
    }

    @Test
    @DisplayName("faucet은 amount와 amountHuman을 동시에 받으면 400을 반환한다")
    void faucetShouldRejectWhenBothRawAndHumanAreProvided() {
        UUID toUserId = UUID.randomUUID();
        when(walletService.getAddress(toUserId)).thenReturn("0xbuyer");

        FaucetRequest request = new FaucetRequest(toUserId, BigInteger.ONE, new BigDecimal("1"));

        assertThatThrownBy(() -> adminAssetService.faucetMockUsd(request, "idem-invalid-both"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getMessage()).contains("either amount(raw) or amountHuman");
            });
    }

    @Test
    @DisplayName("faucet은 amountHuman 소수점 18자리를 초과하면 400을 반환한다")
    void faucetShouldRejectWhenHumanScaleExceeds18() {
        UUID toUserId = UUID.randomUUID();
        when(walletService.getAddress(toUserId)).thenReturn("0xbuyer");

        FaucetRequest request = new FaucetRequest(toUserId, null, new BigDecimal("1.0000000000000000001"));

        assertThatThrownBy(() -> adminAssetService.faucetMockUsd(request, "idem-invalid-scale"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getMessage()).contains("up to 18 decimal places");
            });
    }

    private Function mockFunction() {
        return org.mockito.Mockito.mock(Function.class);
    }

    private OutboxTxEntity outboxTx(String outboxId) {
        OutboxTxEntity tx = new OutboxTxEntity();
        tx.setOutboxId(UUID.fromString(outboxId));
        tx.setTxType("FAUCET_MUSD");
        tx.setStatus("CREATED");
        return tx;
    }
}
