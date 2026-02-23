package io.rwa.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.rwa.server.config.RwaProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserAuthFilterJwtModesTest {

    private HttpServer jwksServer;
    private RSAKey jwksSigningKey;
    private String jwksUrl;
    private AtomicReference<byte[]> jwksBody;
    private AtomicReference<byte[]> firstJwksBody;
    private AtomicInteger jwksFetchCount;

    @BeforeEach
    void setUp() throws Exception {
        jwksSigningKey = new RSAKeyGenerator(2048)
            .keyID("test-kid")
            .generate();

        JWKSet jwkSet = new JWKSet(jwksSigningKey.toPublicJWK());
        this.jwksBody = new AtomicReference<>(jwkSet.toJSONObject().toString().getBytes(StandardCharsets.UTF_8));
        this.firstJwksBody = new AtomicReference<>(null);
        this.jwksFetchCount = new AtomicInteger(0);

        jwksServer = HttpServer.create(new InetSocketAddress(0), 0);
        jwksServer.createContext("/.well-known/jwks.json", exchange -> {
            int count = jwksFetchCount.getAndIncrement();
            byte[] initial = firstJwksBody.get();
            byte[] responseBody = (count == 0 && initial != null) ? initial : jwksBody.get();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        jwksServer.start();
        jwksUrl = "http://localhost:" + jwksServer.getAddress().getPort() + "/.well-known/jwks.json";
    }

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS 유효 토큰은 보호 경로를 통과한다")
    void jwksValidTokenShouldPass() throws Exception {
        RwaProperties properties = jwksProperties();
        UserAuthFilter filter = newFilter(properties);

        String token = signRsaToken(jwksSigningKey, "rwa-id-server", "rwa-custody", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
        assertThat(request.getAttribute(UserPrincipalContext.REQUEST_ATTR)).isInstanceOf(UserPrincipal.class);
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS issuer 불일치 토큰은 401을 반환한다")
    void jwksIssuerMismatchShouldFail() throws Exception {
        UserAuthFilter filter = newFilter(jwksProperties());

        String token = signRsaToken(jwksSigningKey, "wrong-issuer", "rwa-custody", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS audience 불일치 토큰은 401을 반환한다")
    void jwksAudienceMismatchShouldFail() throws Exception {
        UserAuthFilter filter = newFilter(jwksProperties());

        String token = signRsaToken(jwksSigningKey, "rwa-id-server", "wrong-aud", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS 만료 토큰은 401을 반환한다")
    void jwksExpiredTokenShouldFail() throws Exception {
        UserAuthFilter filter = newFilter(jwksProperties());

        String token = signRsaToken(jwksSigningKey, "rwa-id-server", "rwa-custody", Instant.now().minusSeconds(120));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS 서명 불일치 토큰은 401을 반환한다")
    void jwksSignatureMismatchShouldFail() throws Exception {
        UserAuthFilter filter = newFilter(jwksProperties());

        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker-kid").generate();
        String token = signRsaToken(attackerKey, "rwa-id-server", "rwa-custody", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("AUTH_MODE=JWKS 키 로테이션 시 강제 refresh 후 유효 토큰을 통과시킨다")
    void jwksShouldRefreshOnceWhenSignatureMismatchOccurs() throws Exception {
        RSAKey oldKey = new RSAKeyGenerator(2048).keyID("old-kid").generate();
        RSAKey newKey = new RSAKeyGenerator(2048).keyID("new-kid").generate();

        firstJwksBody.set(new JWKSet(oldKey.toPublicJWK()).toJSONObject().toString().getBytes(StandardCharsets.UTF_8));
        jwksBody.set(new JWKSet(newKey.toPublicJWK()).toJSONObject().toString().getBytes(StandardCharsets.UTF_8));

        UserAuthFilter filter = newFilter(jwksProperties());
        String token = signRsaToken(newKey, "rwa-id-server", "rwa-custody", Instant.now().plusSeconds(300));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
        assertThat(jwksFetchCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("AUTH_REQUIRED_PATHS 보호 경로는 Bearer 토큰이 없으면 401을 반환한다")
    void requiredPathWithoutBearerShouldFail() throws Exception {
        UserAuthFilter filter = newFilter(jwksProperties());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    @DisplayName("AUTH_MODE=HMAC 유효 토큰은 보호 경로를 통과한다")
    void hmacValidTokenShouldPass() throws Exception {
        String secretBase64 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        RwaProperties properties = hmacProperties(secretBase64);
        UserAuthFilter filter = newFilter(properties);

        String token = signHmacToken(secretBase64, "rwa-id-server", "rwa-custody", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.chainCalled()).isTrue();
    }

    @Test
    @DisplayName("AUTH_MODE=HMAC 서명 불일치 토큰은 401을 반환한다")
    void hmacSignatureMismatchShouldFail() throws Exception {
        String secretBase64 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        RwaProperties properties = hmacProperties(secretBase64);
        UserAuthFilter filter = newFilter(properties);

        String wrongSecret = Base64.getEncoder().encodeToString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8));
        String token = signHmacToken(wrongSecret, "rwa-id-server", "rwa-custody", Instant.now().plusSeconds(300));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trade/list");
        request.addHeader("Authorization", "Bearer " + token);

        InvocationResult result = invoke(filter, request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    private UserAuthFilter newFilter(RwaProperties properties) {
        return new UserAuthFilter(
            properties,
            new ObjectMapper(),
            new JwksJwtVerifier(properties),
            new HmacJwtVerifier(properties)
        );
    }

    private InvocationResult invoke(UserAuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            chainCalled.set(true);
            ((HttpServletResponse) res).setStatus(200);
        };
        filter.doFilter(request, response, chain);
        return new InvocationResult(response.getStatus(), chainCalled.get());
    }

    private RwaProperties jwksProperties() {
        RwaProperties properties = new RwaProperties();
        properties.getAuth().setMode("JWKS");
        properties.getAuth().setJwksUrl(jwksUrl);
        properties.getAuth().setJwtIssuer("rwa-id-server");
        properties.getAuth().setJwtAudience("rwa-custody");
        properties.getAuth().setClockSkewSeconds(60);
        properties.getAuth().setRequiredPaths(List.of("/trade/**"));
        return properties;
    }

    private RwaProperties hmacProperties(String secretBase64) {
        RwaProperties properties = new RwaProperties();
        properties.getAuth().setMode("HMAC");
        properties.getAuth().setHmacSecretBase64(secretBase64);
        properties.getAuth().setJwtIssuer("rwa-id-server");
        properties.getAuth().setJwtAudience("rwa-custody");
        properties.getAuth().setClockSkewSeconds(60);
        properties.getAuth().setRequiredPaths(List.of("/trade/**"));
        return properties;
    }

    private String signRsaToken(RSAKey signingKey, String issuer, String audience, Instant exp) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .build();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build(),
            claims
        );
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private String signHmacToken(String secretBase64, String issuer, String audience, Instant exp) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(secretBase64)));
        return jwt.serialize();
    }

    private record InvocationResult(int status, boolean chainCalled) {
    }
}
