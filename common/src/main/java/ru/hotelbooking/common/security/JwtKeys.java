package ru.hotelbooking.common.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;

public final class JwtKeys {
    private JwtKeys() {}

    public static RSAPublicKey publicKey(Resource resource) {
        try {
            return (RSAPublicKey)
                    KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(read(resource)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось прочитать публичный JWT-ключ: " + resource, e);
        }
    }

    public static RSAPrivateKey privateKey(Resource resource) {
        try {
            return (RSAPrivateKey)
                    KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(read(resource)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось прочитать приватный JWT-ключ: " + resource, e);
        }
    }

    private static byte[] read(Resource resource) throws Exception {
        try (var stream = resource.getInputStream()) {
            var pem =
                    new String(stream.readAllBytes(), StandardCharsets.US_ASCII)
                            .replaceAll("-----[^-]+-----", "")
                            .replaceAll("[^A-Za-z0-9+/=]", "");
            return Base64.getDecoder().decode(pem);
        }
    }
}
