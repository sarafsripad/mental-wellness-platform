package org.mentalwellness.api.shared.security;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bearer-token settings ({@code wellness.security.jwt.*}).
 *
 * @param issuer     expected {@code iss} claim, e.g. {@code http://localhost:8081/realms/wellness-local}
 * @param jwkSetUri  where to fetch signing keys; may differ from the issuer host inside Docker
 * @param audience   required {@code aud} value
 */
@Validated
@ConfigurationProperties("wellness.security.jwt")
public record JwtProperties(@NotBlank String issuer, @NotNull URI jwkSetUri, @NotBlank String audience) {
}
