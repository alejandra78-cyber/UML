package com.example.demo.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled JWT (HS256) issuance and verification, using only the JDK's
 * {@code javax.crypto} APIs -- no jjwt/nimbus dependency is added to the
 * pom, per the isolation constraints of this task.
 *
 * <p><b>Design note / production recommendation:</b> a compact JWT library
 * such as {@code io.jsonwebtoken:jjwt-api} + {@code jjwt-impl} +
 * {@code jjwt-jackson} would be preferable in production (battle-tested
 * parsing, key rotation support, standard claim validation, JWK support).
 * This manual implementation is intentionally minimal: it supports exactly
 * the three claims this application needs ({@code sub}, {@code email},
 * {@code exp}) and performs constant-time signature comparison, but it is
 * not a general-purpose JOSE/JWT implementation.</p>
 */
@Service
public class JwtService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static final Pattern SUB_PATTERN = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\"email\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    /**
     * Development-only default secret (>= 32 bytes so HS256 gets a
     * reasonably sized key even if nobody overrides it locally). This
     * MUST be overridden via the {@code app.jwt.secret} property (e.g. an
     * {@code APP_JWT_SECRET} environment variable mapped in
     * application.properties, or a container/Vault-injected value) in any
     * shared or production environment. Never rely on this default outside
     * of local development.
     */
    @Value("${app.jwt.secret:dev-only-insecure-default-secret-change-me-please-32bytes-min}")
    private String secret;

    @Value("${app.jwt.expiration-minutes:60}")
    private long expirationMinutes;

    public String generateToken(UUID userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationMinutes * 60);
        String payloadJson = "{\"sub\":\"" + escape(userId.toString()) + "\","
                + "\"email\":\"" + escape(email) + "\","
                + "\"iat\":" + now.getEpochSecond() + ","
                + "\"exp\":" + exp.getEpochSecond() + "}";

        String headerB64 = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;
        String signature = sign(signingInput);
        return signingInput + "." + signature;
    }

    public Claims parseAndValidate(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Token is empty");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("Malformed JWT: expected header.payload.signature");
        }
        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput);
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new JwtException("Invalid JWT signature");
        }

        String payloadJson;
        try {
            payloadJson = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Malformed JWT payload encoding");
        }

        String subject = extract(SUB_PATTERN, payloadJson, "sub");
        String email = extract(EMAIL_PATTERN, payloadJson, "email");
        long exp = Long.parseLong(extract(EXP_PATTERN, payloadJson, "exp"));

        if (Instant.now().getEpochSecond() > exp) {
            throw new JwtException("Token expired");
        }

        return new Claims(UUID.fromString(subject), email, Instant.ofEpochSecond(exp));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to sign JWT", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String extract(Pattern pattern, String json, String claimName) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new JwtException("Missing claim: " + claimName);
        }
        return matcher.group(1);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Decoded token claims.
     */
    public record Claims(UUID userId, String email, Instant expiresAt) {
    }

    public static class JwtException extends RuntimeException {
        public JwtException(String message) {
            super(message);
        }
    }
}
