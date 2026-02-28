package com.example.cryptoorder.auth.security;

import com.example.cryptoorder.auth.config.AuthServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void initLoadsRsaKeysFromPemPaths() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        Path privatePath = tempDir.resolve("jwt_private.pem");
        Path publicPath = tempDir.resolve("jwt_public.pem");
        Files.writeString(privatePath, toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(publicPath, toPem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        AuthServerProperties properties = new AuthServerProperties();
        properties.setMode(AuthServerProperties.Mode.JWKS);
        properties.setAllowEphemeralKeys(false);
        properties.setJwtPrivateKeyPath(privatePath.toString());
        properties.setJwtPublicKeyPath(publicPath.toString());

        JwtKeyProvider provider = new JwtKeyProvider(properties);
        provider.init();

        assertThat(provider.getRsaPrivateKey()).isNotNull();
        assertThat(provider.getPublicRsaJwk().getKeyID()).isEqualTo(properties.getJwtKeyId());
    }

    @Test
    void initRejectsPrivateKeyBase64AndPathTogether() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        Path privatePath = tempDir.resolve("jwt_private.pem");
        Path publicPath = tempDir.resolve("jwt_public.pem");
        Files.writeString(privatePath, toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(publicPath, toPem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        AuthServerProperties properties = new AuthServerProperties();
        properties.setMode(AuthServerProperties.Mode.JWKS);
        properties.setAllowEphemeralKeys(false);
        properties.setJwtPrivateKeyPath(privatePath.toString());
        properties.setJwtPrivateKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setJwtPublicKeyPath(publicPath.toString());

        JwtKeyProvider provider = new JwtKeyProvider(properties);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT RSA 키 초기화에 실패했습니다.")
                .hasRootCauseMessage("auth.jwt-private-key-base64와 auth.jwt-private-key-path는 동시에 설정할 수 없습니다.");
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String toPem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
