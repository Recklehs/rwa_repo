package com.example.cryptoorder.auth.controller;

import com.example.cryptoorder.Account.service.UserAccountService;
import com.example.cryptoorder.auth.dto.AuthSignupRequest;
import com.example.cryptoorder.common.idempotency.IdempotencyRecord;
import com.example.cryptoorder.common.idempotency.IdempotencyRecordRepository;
import com.example.cryptoorder.common.idempotency.IdempotencyStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "auth.mode=JWKS",
        "auth.allow-ephemeral-keys=true",
        "custody.provision.enabled=false"
})
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    void signup_issuesAccessAndRefreshTokenWithRequiredClaims() throws Exception {
        String response = mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-member-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원A",
                                  "phone":"010-1234-5678",
                                  "birthDate":"1990-01-01",
                                  "loginId":"member-a",
                                  "password":"password123",
                                  "provider":"MEMBER",
                                  "externalUserId":"ext-member-a"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        String accessToken = body.get("accessToken").asText();
        SignedJWT jwt = SignedJWT.parse(accessToken);

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("rwa-id-server");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("rwa-custody");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getClaim("provider")).isEqualTo("MEMBER");
        assertThat(jwt.getJWTClaimsSet().getClaim("externalUserId")).isEqualTo("ext-member-a");
        assertThat(jwt.getJWTClaimsSet().getClaim("roles")).isEqualTo(List.of("USER"));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(jwt.getJWTClaimsSet().getIssueTime());
    }

    @Test
    void login_and_refresh_rotateRefreshToken() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-member-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원B",
                                  "phone":"010-9999-0000",
                                  "birthDate":"1992-02-02",
                                  "loginId":"member-b",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk());

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .header("Idempotency-Key", "login-member-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId":"member-b",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String firstRefresh = objectMapper.readTree(loginResponse).get("refreshToken").asText();

        String refreshResponse = mockMvc.perform(post("/auth/refresh")
                        .header("Idempotency-Key", "refresh-member-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken":"%s"
                                }
                                """.formatted(firstRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondRefresh = objectMapper.readTree(refreshResponse).get("refreshToken").asText();
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);
    }

    @Test
    void jwks_endpoint_exposesPublicKey() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kid").value("member-server-key-1"));
    }

    @Test
    void login_withInvalidCredential_returns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .header("Idempotency-Key", "login-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId":"unknown",
                                  "password":"wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signup_withoutIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원C",
                                  "phone":"010-1111-9999",
                                  "birthDate":"1994-03-03",
                                  "loginId":"member-c",
                                  "password":"password123"
                                }
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signup_withTooLongIdempotencyKey_returns400() throws Exception {
        String tooLongKey = IntStream.range(0, 201)
                .mapToObj(i -> "a")
                .collect(Collectors.joining());

        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", tooLongKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원키",
                                  "phone":"010-4444-3333",
                                  "birthDate":"1991-11-11",
                                  "loginId":"member-key",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_forDeletedUser_returns401() throws Exception {
        String signupResponse = mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-member-d")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원D",
                                  "phone":"010-2222-3333",
                                  "birthDate":"1991-05-06",
                                  "loginId":"member-d",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode signupBody = objectMapper.readTree(signupResponse);
        UUID userId = UUID.fromString(signupBody.get("userId").asText());
        String refreshToken = signupBody.get("refreshToken").asText();

        userAccountService.deleteUser(userId);

        mockMvc.perform(post("/auth/refresh")
                        .header("Idempotency-Key", "refresh-member-d")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken":"%s"
                                }
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signup_sameIdempotencyKeyAndPayload_replaysSameResponse() throws Exception {
        String first = mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원E",
                                  "phone":"010-5555-6666",
                                  "birthDate":"1990-09-10",
                                  "loginId":"member-e",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원E",
                                  "phone":"010-5555-6666",
                                  "birthDate":"1990-09-10",
                                  "loginId":"member-e",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstBody = objectMapper.readTree(first);
        JsonNode secondBody = objectMapper.readTree(second);
        assertThat(secondBody.get("accessToken").asText()).isEqualTo(firstBody.get("accessToken").asText());
        assertThat(secondBody.get("refreshToken").asText()).isEqualTo(firstBody.get("refreshToken").asText());

        IdempotencyRecord stored = idempotencyRecordRepository.findByRequestKey("POST:/auth/signup:signup-replay-key")
                .orElseThrow();
        assertThat(stored.isResponseEncrypted()).isTrue();
        assertThat(stored.getResponseBody()).doesNotContain(firstBody.get("accessToken").asText());
        assertThat(stored.getResponseBody()).doesNotContain(firstBody.get("refreshToken").asText());
    }

    @Test
    void signup_sameIdempotencyKeyWithDifferentPayload_returns409() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원F",
                                  "phone":"010-7777-8888",
                                  "birthDate":"1988-01-01",
                                  "loginId":"member-f",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원F2",
                                  "phone":"010-7777-8888",
                                  "birthDate":"1988-01-01",
                                  "loginId":"member-f2",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void signup_whenInProgressDuplicate_returns202() throws Exception {
        String payload = """
                {
                  "name":"회원G",
                  "phone":"010-9090-9090",
                  "birthDate":"1987-07-07",
                  "loginId":"member-g",
                  "password":"password123"
                }
                """;

        String payloadHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        objectMapper.writeValueAsBytes(objectMapper.readValue(payload, AuthSignupRequest.class))
                )
        );

        idempotencyRecordRepository.save(IdempotencyRecord.builder()
                .requestKey("POST:/auth/signup:signup-in-progress-key")
                .payloadHash(payloadHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .lockExpiresAt(LocalDateTime.now().plusMinutes(1))
                .build());

        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-in-progress-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void signup_whenStaleInProgressRecordExists_recoversAndCompletes() throws Exception {
        String payload = """
                {
                  "name":"회원H",
                  "phone":"010-3030-4040",
                  "birthDate":"1993-03-04",
                  "loginId":"member-h",
                  "password":"password123"
                }
                """;

        String payloadHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        objectMapper.writeValueAsBytes(objectMapper.readValue(payload, AuthSignupRequest.class))
                )
        );

        idempotencyRecordRepository.save(IdempotencyRecord.builder()
                .requestKey("POST:/auth/signup:signup-stale-key")
                .payloadHash(payloadHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .lockExpiresAt(LocalDateTime.now().minusMinutes(1))
                .build());

        mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-stale-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }
}
