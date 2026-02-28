package com.example.cryptoorder.auth.service;

import com.example.cryptoorder.auth.config.CustodyProvisionProperties;
import com.example.cryptoorder.auth.dto.CustodyWalletProvisionRequest;
import com.example.cryptoorder.auth.dto.CustodyWalletProvisionResponse;
import com.example.cryptoorder.common.api.UpstreamServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustodyProvisionClient {

    private final CustodyProvisionProperties custodyProvisionProperties;
    private final RestClient.Builder restClientBuilder;

    public void provisionWallet(UUID userId, String provider, String externalUserId) {
        if (!custodyProvisionProperties.isEnabled()) {
            return;
        }

        validateConfiguration();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(custodyProvisionProperties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(custodyProvisionProperties.getReadTimeoutMillis());

        RestClient restClient = restClientBuilder
                .baseUrl(custodyProvisionProperties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();

        CustodyWalletProvisionRequest request = new CustodyWalletProvisionRequest(userId, provider, externalUserId);
        String idempotencyKey = "member-signup-" + userId;

        try {
            ResponseEntity<CustodyWalletProvisionResponse> response = restClient.post()
                    .uri(custodyProvisionProperties.getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(custodyProvisionProperties.getServiceTokenHeader(), custodyProvisionProperties.getServiceToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .toEntity(CustodyWalletProvisionResponse.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new UpstreamServiceException("커스터디 지갑 프로비저닝에 실패했습니다. status=" + response.getStatusCode().value());
            }
        } catch (RestClientResponseException e) {
            throw new UpstreamServiceException("커스터디 지갑 프로비저닝에 실패했습니다. status=" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new UpstreamServiceException("커스터디 지갑 프로비저닝 호출에 실패했습니다.", e);
        }
    }

    private void validateConfiguration() {
        if (isBlank(custodyProvisionProperties.getBaseUrl())) {
            throw new UpstreamServiceException("custody.provision.base-url이 필요합니다.");
        }
        if (isBlank(custodyProvisionProperties.getServiceToken())) {
            throw new UpstreamServiceException("custody.provision.service-token이 필요합니다.");
        }
        if (isBlank(custodyProvisionProperties.getServiceTokenHeader())) {
            throw new UpstreamServiceException("custody.provision.service-token-header가 필요합니다.");
        }
        if (isBlank(custodyProvisionProperties.getPath())) {
            throw new UpstreamServiceException("custody.provision.path가 필요합니다.");
        }
        if (custodyProvisionProperties.getConnectTimeoutMillis() <= 0) {
            throw new UpstreamServiceException("custody.provision.connect-timeout-millis는 1 이상이어야 합니다.");
        }
        if (custodyProvisionProperties.getReadTimeoutMillis() <= 0) {
            throw new UpstreamServiceException("custody.provision.read-timeout-millis는 1 이상이어야 합니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
