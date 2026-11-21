package com.example.fluxio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class LoginController {
    private final JwtEncoder encoder;
    private final String username;
    private final byte[] password;

    LoginController(JwtEncoder encoder,
                    @Value("${fluxio.username}") String username,
                    @Value("${fluxio.password}") String password) {
        this.encoder = encoder;
        this.username = username;
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/v1/auth/login")
    Session login(@RequestBody Credentials credentials) {
        if (password.length == 0 || !username.equals(credentials.username())
                || credentials.password() == null
                || !MessageDigest.isEqual(password, credentials.password().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new Session(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
    }

    record Credentials(String username, String password) {}
    record Session(String accessToken) {}
}
