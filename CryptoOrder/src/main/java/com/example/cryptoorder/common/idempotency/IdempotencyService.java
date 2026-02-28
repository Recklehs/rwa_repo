package com.example.cryptoorder.common.idempotency;

import com.example.cryptoorder.common.api.IdempotencyConflictException;
import com.example.cryptoorder.common.api.IdempotencyInProgressException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties idempotencyProperties;
    private final IdempotencyCryptoService idempotencyCryptoService;

    public <T> ResponseEntity<T> execute(
            String operationKey,
            String idempotencyKey,
            Object payload,
            Class<T> responseType,
            Supplier<ResponseEntity<T>> action
    ) {
        String requestKey = operationKey + ":" + idempotencyKey;
        String payloadHash = hashPayload(payload);

        UUID recordId;
        try {
            recordId = begin(requestKey, payloadHash);
        } catch (DataIntegrityViolationException e) {
            ExistingResolution resolution = resolveExisting(requestKey, payloadHash);
            if (resolution.replayRecord != null) {
                return replayResponse(resolution.replayRecord, responseType);
            }
            recordId = resolution.recordId;
        }

        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            fail(recordId);
            throw e;
        }

        complete(recordId, response);
        return response;
    }

    private UUID begin(String requestKey, String payloadHash) {
        return executeInNewTransaction(() -> {
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .requestKey(requestKey)
                    .payloadHash(payloadHash)
                    .status(IdempotencyStatus.IN_PROGRESS)
                    .lockExpiresAt(LocalDateTime.now().plusSeconds(idempotencyProperties.getInProgressTtlSeconds()))
                    .build();
            idempotencyRecordRepository.saveAndFlush(record);
            return record.getId();
        });
    }

    private void complete(UUID recordId, ResponseEntity<?> response) {
        executeInNewTransaction(() -> {
            IdempotencyRecord record = idempotencyRecordRepository.findById(recordId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency 레코드를 찾을 수 없습니다."));

            int statusCode = response.getStatusCode().value();
            String storedBody = null;
            boolean encrypted = false;

            try {
                String bodyJson = serializeBody(response.getBody());
                encrypted = bodyJson != null;
                storedBody = encrypted ? idempotencyCryptoService.encrypt(bodyJson) : null;
            } catch (RuntimeException ignored) {
                // 응답 본문 저장 실패 시에도 완료 상태를 기록해 중복 실행을 방지한다.
            }
            record.markCompleted(statusCode, storedBody, encrypted);
            idempotencyRecordRepository.save(record);
            return null;
        });
    }

    private void fail(UUID recordId) {
        executeInNewTransaction(() -> {
            idempotencyRecordRepository.deleteById(recordId);
            return null;
        });
    }

    private ExistingResolution resolveExisting(String requestKey, String payloadHash) {
        return executeInNewTransaction(() -> {
            IdempotencyRecord existing = idempotencyRecordRepository.findByRequestKeyWithLock(requestKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency 레코드를 찾을 수 없습니다."));

            ensureSamePayload(existing, payloadHash);

            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                if (existing.getLockExpiresAt().isAfter(LocalDateTime.now())) {
                    throw new IdempotencyInProgressException("동일 요청이 처리 중입니다.");
                }

                existing.renewInProgress(LocalDateTime.now().plusSeconds(idempotencyProperties.getInProgressTtlSeconds()));
                idempotencyRecordRepository.save(existing);
                return new ExistingResolution(existing.getId(), null);
            }

            return new ExistingResolution(null, existing);
        });
    }

    private void ensureSamePayload(IdempotencyRecord existing, String payloadHash) {
        if (!existing.getPayloadHash().equals(payloadHash)) {
            throw new IdempotencyConflictException("동일 Idempotency-Key에 서로 다른 요청 본문을 사용할 수 없습니다.");
        }
    }

    private <T> ResponseEntity<T> replayResponse(IdempotencyRecord existing, Class<T> responseType) {
        Integer statusCode = existing.getResponseStatus();
        if (statusCode == null) {
            throw new IdempotencyInProgressException("동일 요청이 처리 중입니다.");
        }

        String bodyJson = existing.getResponseBody();
        if (existing.isResponseEncrypted() && bodyJson != null) {
            bodyJson = idempotencyCryptoService.decrypt(bodyJson);
        }

        T body = deserializeBody(bodyJson, responseType);
        return ResponseEntity.status(statusCode).body(body);
    }

    private String hashPayload(Object payload) {
        try {
            byte[] source = objectMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source));
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency payload 해시 생성에 실패했습니다.", e);
        }
    }

    private String serializeBody(Object body) {
        if (body == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency 응답 직렬화에 실패했습니다.", e);
        }
    }

    private <T> T deserializeBody(String bodyJson, Class<T> responseType) {
        if (bodyJson == null) {
            return null;
        }

        try {
            return objectMapper.readValue(bodyJson.getBytes(StandardCharsets.UTF_8), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency 응답 역직렬화에 실패했습니다.", e);
        }
    }

    private <T> T executeInNewTransaction(Supplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> supplier.get());
    }

    private record ExistingResolution(UUID recordId, IdempotencyRecord replayRecord) {
    }
}
