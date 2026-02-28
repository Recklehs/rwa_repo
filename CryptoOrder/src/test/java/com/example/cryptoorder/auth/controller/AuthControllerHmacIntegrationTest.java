package com.example.cryptoorder.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "auth.mode=HMAC",
        "auth.hmac-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "custody.provision.enabled=false"
})
class AuthControllerHmacIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void signup_inHmacMode_issuesHs256Token() throws Exception {
        String response = mockMvc.perform(post("/auth/signup")
                        .header("Idempotency-Key", "signup-hmac-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"회원HM",
                                  "phone":"010-3123-4123",
                                  "birthDate":"1990-12-12",
                                  "loginId":"member-hmac",
                                  "password":"password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        SignedJWT jwt = SignedJWT.parse(body.get("accessToken").asText());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("rwa-id-server");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("rwa-custody");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isNotBlank();
    }
}
