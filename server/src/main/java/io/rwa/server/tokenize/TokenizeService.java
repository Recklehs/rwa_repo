package io.rwa.server.tokenize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.server.common.ApiException;
import io.rwa.server.common.CanonicalJsonService;
import io.rwa.server.config.RwaProperties;
import io.rwa.server.outbox.OutboxEventPublisher;
import io.rwa.server.publicdata.PropertyClassEntity;
import io.rwa.server.publicdata.PropertyClassRepository;
import io.rwa.server.publicdata.PublicDataService;
import io.rwa.server.publicdata.UnitEntity;
import io.rwa.server.publicdata.UnitRepository;
import io.rwa.server.tx.OutboxTxEntity;
import io.rwa.server.tx.TxOrchestratorService;
import io.rwa.server.web3.ContractGatewayService;
import jakarta.transaction.Transactional;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

@Service
public class TokenizeService {

    private static final Duration TOKENIZE_WAIT_TIMEOUT = Duration.ofMinutes(10);

    private final PropertyClassRepository classRepository;
    private final UnitRepository unitRepository;
    private final ContractGatewayService contractGatewayService;
    private final TxOrchestratorService txOrchestratorService;
    private final CanonicalJsonService canonicalJsonService;
    private final ObjectMapper objectMapper;
    private final RwaProperties properties;
    private final OutboxEventPublisher outboxEventPublisher;

    public TokenizeService(
        PropertyClassRepository classRepository,
        UnitRepository unitRepository,
        ContractGatewayService contractGatewayService,
        TxOrchestratorService txOrchestratorService,
        CanonicalJsonService canonicalJsonService,
        ObjectMapper objectMapper,
        RwaProperties properties,
        OutboxEventPublisher outboxEventPublisher
    ) {
        this.classRepository = classRepository;
        this.unitRepository = unitRepository;
        this.contractGatewayService = contractGatewayService;
        this.txOrchestratorService = txOrchestratorService;
        this.canonicalJsonService = canonicalJsonService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional
    public TokenizeResult tokenizeClass(String classId, TokenizeRequest request, String idempotencyKey) {
        PropertyClassEntity clazz = classRepository.findById(classId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Class not found: " + classId));

        int chunkSize = request == null || request.chunkSize() == null || request.chunkSize() <= 0
            ? 100
            : request.chunkSize();
        long issuedAt = request == null || request.issuedAt() == null
            ? Instant.now().getEpochSecond()
            : request.issuedAt();

        String docHash = computeDocHash(clazz);

        String issuerPrivateKey = requirePrivateKey(properties.getIssuerPrivateKey(), "ISSUER_PRIVATE_KEY");
        String issuerAddress = Credentials.create(issuerPrivateKey).getAddress().toLowerCase();
        String treasuryAddress = Credentials.create(requirePrivateKey(properties.getTreasuryPrivateKey(), "TREASURY_PRIVATE_KEY"))
            .getAddress()
            .toLowerCase();

        List<java.util.UUID> outboxIds = new ArrayList<>();
        ContractGatewayService.RegistryClassInfo chainClass = contractGatewayService.getRegistryClass(classId);
        if (chainClass.unitCount() > 0 && chainClass.unitCount() != clazz.getUnitCount()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "On-chain class unitCount mismatch. db=" + clazz.getUnitCount() + ", chain=" + chainClass.unitCount()
            );
        }
        if (chainClass.unitCount() == 0) {
            OutboxTxEntity reserveTx = txOrchestratorService.submitContractTx(
                idempotencyKey + ":tokenize:reserve:" + classId,
                issuerAddress,
                issuerPrivateKey,
                contractGatewayService.propertyTokenizerAddress(),
                contractGatewayService.fnReserveAndRegisterClass(classId, docHash, clazz.getUnitCount(), issuedAt),
                "TOKENIZE_RESERVE_REGISTER",
                objectMapper.createObjectNode()
                    .put("classId", classId)
                    .put("docHash", docHash)
                    .put("unitCount", clazz.getUnitCount())
                    .put("issuedAt", issuedAt)
            );
            outboxIds.add(reserveTx.getOutboxId());
            waitForMinedOrThrow(reserveTx.getOutboxId(), "reserve");
            chainClass = contractGatewayService.getRegistryClass(classId);
        }

        if (chainClass.unitCount() == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Class is not registered on-chain after reserve step");
        }

        BigInteger baseTokenId = chainClass.baseTokenId();
        int unitCount = chainClass.unitCount();
        List<UUID> mintOutboxIds = new ArrayList<>();
        for (int start = 0; start < unitCount; start += chunkSize) {
            int count = Math.min(chunkSize, unitCount - start);
            mintOutboxIds.addAll(submitMintChunkWithFallback(
                idempotencyKey,
                classId,
                start,
                count,
                baseTokenId,
                issuerAddress,
                issuerPrivateKey,
                treasuryAddress
            ));
        }
        outboxIds.addAll(mintOutboxIds);

        for (UUID mintOutboxId : mintOutboxIds) {
            waitForMinedOrThrow(mintOutboxId, "mint");
        }

        chainClass = contractGatewayService.getRegistryClass(classId);
        boolean allMinted = areAllUnitsMinted(chainClass.baseTokenId(), chainClass.unitCount());
        String previousStatus = clazz.getStatus();

        clazz.setDocHash(chainClass.docHash());
        clazz.setIssuedAt(Instant.ofEpochSecond(chainClass.issuedAt()));
        clazz.setBaseTokenId(chainClass.baseTokenId());
        clazz.setStatus(allMinted ? PublicDataService.CLASS_STATUS_TOKENIZED : PublicDataService.CLASS_STATUS_IMPORTED);
        clazz.setUpdatedAt(Instant.now());

        if (chainClass.unitCount() > 0) {
            List<UnitEntity> units = unitRepository.findByClassIdOrderByUnitNoAsc(classId);
            for (UnitEntity unit : units) {
                unit.setTokenId(chainClass.baseTokenId().add(BigInteger.valueOf(unit.getUnitNo() - 1L)));
                if (allMinted) {
                    unit.setStatus(PublicDataService.CLASS_STATUS_TOKENIZED);
                } else if (unit.getStatus() == null) {
                    unit.setStatus(PublicDataService.CLASS_STATUS_IMPORTED);
                }
                unitRepository.save(unit);
            }
            unitRepository.flush();
        }

        classRepository.save(clazz);
        classRepository.flush();

        if (allMinted && !PublicDataService.CLASS_STATUS_TOKENIZED.equals(previousStatus)) {
            ObjectNode payload = objectMapper.createObjectNode()
                .put("classId", classId)
                .put("docHash", clazz.getDocHash())
                .put("status", clazz.getStatus());
            outboxEventPublisher.publish("Class", classId, "ClassTokenized", classId, payload);
        }

        return new TokenizeResult(classId, clazz.getDocHash(), clazz.getStatus(), outboxIds);
    }

    private String computeDocHash(PropertyClassEntity clazz) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("classId", clazz.getClassId());
        root.put("kaptCode", clazz.getKaptCode());
        root.put("classKey", clazz.getClassKey());
        root.put("unitCount", clazz.getUnitCount());

        ArrayNode units = objectMapper.createArrayNode();
        for (UnitEntity unit : unitRepository.findByClassIdOrderByUnitNoAsc(clazz.getClassId())) {
            ObjectNode u = objectMapper.createObjectNode();
            u.put("unitId", unit.getUnitId());
            u.put("unitNo", unit.getUnitNo());
            units.add(u);
        }
        root.set("units", units);

        String canonical = canonicalJsonService.canonicalString(root);
        return contractGatewayService.keccak256Hex(contractGatewayService.utf8(canonical));
    }

    private String requirePrivateKey(String key, String envName) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, envName + " is required");
        }
        return key;
    }

    private void waitForMinedOrThrow(UUID outboxId, String phase) {
        try {
            txOrchestratorService.waitForMined(outboxId, TOKENIZE_WAIT_TIMEOUT);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.ACCEPTED) {
                throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Tokenize " + phase + " tx not mined within timeout: " + outboxId
                );
            }
            throw e;
        }
    }

    private List<UUID> submitMintChunkWithFallback(
        String idempotencyKey,
        String classId,
        int start,
        int count,
        BigInteger baseTokenId,
        String issuerAddress,
        String issuerPrivateKey,
        String treasuryAddress
    ) {
        List<UUID> outboxIds = new ArrayList<>();
        try {
            OutboxTxEntity mintTx = submitMintTx(idempotencyKey, classId, start, count, issuerAddress, issuerPrivateKey, treasuryAddress);
            outboxIds.add(mintTx.getOutboxId());
            return outboxIds;
        } catch (ApiException e) {
            if (!looksLikeTokenAlreadyMinted(e)) {
                throw e;
            }
            if (count == 1) {
                if (isUnitMinted(baseTokenId, start)) {
                    return outboxIds;
                }
                throw e;
            }
        }

        for (int offset = start; offset < start + count; offset++) {
            if (isUnitMinted(baseTokenId, offset)) {
                continue;
            }
            try {
                OutboxTxEntity singleMint = submitMintTx(
                    idempotencyKey,
                    classId,
                    offset,
                    1,
                    issuerAddress,
                    issuerPrivateKey,
                    treasuryAddress
                );
                outboxIds.add(singleMint.getOutboxId());
            } catch (ApiException e) {
                if (looksLikeTokenAlreadyMinted(e) && isUnitMinted(baseTokenId, offset)) {
                    continue;
                }
                throw e;
            }
        }
        return outboxIds;
    }

    private OutboxTxEntity submitMintTx(
        String idempotencyKey,
        String classId,
        int startOffset,
        int count,
        String issuerAddress,
        String issuerPrivateKey,
        String treasuryAddress
    ) {
        return txOrchestratorService.submitContractTx(
            idempotencyKey + ":tokenize:mint:" + classId + ":" + startOffset + ":" + count,
            issuerAddress,
            issuerPrivateKey,
            contractGatewayService.propertyTokenizerAddress(),
            contractGatewayService.fnMintUnits(classId, startOffset, count, treasuryAddress),
            "TOKENIZE_MINT_CHUNK",
            objectMapper.createObjectNode()
                .put("classId", classId)
                .put("startOffset", startOffset)
                .put("count", count)
        );
    }

    private boolean looksLikeTokenAlreadyMinted(ApiException e) {
        String message = e.getMessage();
        return message != null && (
            message.contains("TokenAlreadyMinted")
                || message.toLowerCase().contains("execution reverted")
        );
    }

    private boolean areAllUnitsMinted(BigInteger baseTokenId, int unitCount) {
        if (baseTokenId == null || unitCount <= 0) {
            return false;
        }
        for (int i = 0; i < unitCount; i++) {
            if (!isUnitMinted(baseTokenId, i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isUnitMinted(BigInteger baseTokenId, int offset) {
        BigInteger tokenId = baseTokenId.add(BigInteger.valueOf(offset));
        return contractGatewayService.shareTotalSupply(tokenId).compareTo(BigInteger.ZERO) > 0;
    }

    public record TokenizeResult(
        String classId,
        String docHash,
        String status,
        List<java.util.UUID> outboxIds
    ) {
    }
}
