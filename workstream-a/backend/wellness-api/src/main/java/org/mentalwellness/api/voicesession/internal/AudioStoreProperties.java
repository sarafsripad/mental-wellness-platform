package org.mentalwellness.api.voicesession.internal;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3-compatible audio storage ({@code wellness.storage.*}). Credentials have no defaults.
 *
 * @param endpoint  S3 API endpoint, e.g. {@code http://localhost:8333} (local SeaweedFS)
 * @param region    signing region
 * @param bucket    audio bucket (private, SSE-S3 by default)
 * @param accessKey bucket-scoped access key of the wellness-api
 * @param secretKey its secret
 */
@Validated
@ConfigurationProperties("wellness.storage")
record AudioStoreProperties(@NotNull URI endpoint, @NotBlank String region, @NotBlank String bucket,
		@NotBlank String accessKey, @NotBlank String secretKey) {
}
