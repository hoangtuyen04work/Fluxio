package com.example.fluxio;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class ApiSecurity {
    @Bean
    SecretKey sessionKey() throws NoSuchAlgorithmException {
        var generator = KeyGenerator.getInstance("HmacSHA256");
        generator.init(256);
        return generator.generateKey();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey sessionKey) {
        return NimbusJwtDecoder.withSecretKey(sessionKey).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey sessionKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(sessionKey));
    }

    @Bean
    SecurityFilterChain apiFilter(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> {}))
                .build();
    }
}
