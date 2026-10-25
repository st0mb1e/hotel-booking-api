package ru.hotelbooking.hotel;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

final class TestKeys {
    static final RSAKey KEY;
    static final Path PUBLIC;
    static final Path PRIVATE;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test").generate();
            PUBLIC = Files.createTempFile("hotel-test-public-", ".pem");
            PRIVATE = Files.createTempFile("hotel-test-private-", ".pem");
            Files.writeString(PUBLIC, pem("PUBLIC KEY", KEY.toRSAPublicKey().getEncoded()));
            Files.writeString(PRIVATE, pem("PRIVATE KEY", KEY.toRSAPrivateKey().getEncoded()));
            PUBLIC.toFile().deleteOnExit();
            PRIVATE.toFile().deleteOnExit();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END "
                + label
                + "-----\n";
    }

    static String user(String subject, String role) {
        return token(
                subject, List.of(role), "hotel-booking-api", null, Instant.now().plusSeconds(3600));
    }

    static String service() {
        return token(
                "booking-service",
                List.of(),
                "hotel-service",
                "rooms:write",
                Instant.now().plusSeconds(300));
    }

    static String token(
            String subject, List<String> roles, String audience, String scope, Instant expiresAt) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
        var claims =
                JwtClaimsSet.builder()
                        .issuer("hotel-booking")
                        .subject(subject)
                        .audience(List.of(audience))
                        .issuedAt(Instant.now().minusSeconds(7200))
                        .expiresAt(expiresAt)
                        .claim("roles", roles);
        if (scope != null) claims.claim("scope", scope);
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(),
                                claims.build()))
                .getTokenValue();
    }
}
