package org.mentalwellness.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Real infrastructure for integration tests, at the same versions as deploy/local: PostgreSQL, and
 * SeaweedFS object storage with SSE-S3 enabled, a root identity for setup and a bucket-scoped key
 * for the API. No database or storage mocks.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String BUCKET = "wellness-audio";
	static final String ROOT_KEY = "testrootkey00001";
	static final String ROOT_SECRET = "test-only-root-secret-0000000000000001";
	static final String APP_KEY = "testappkey000001";
	static final String APP_SECRET = "test-only-app-secret-00000000000000001";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16.4"));
	}

	@Bean
	GenericContainer<?> storageContainer() {
		String identities = """
				{"identities":[
				 {"name":"root","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","Write","List","Tagging"]},
				 {"name":"wellness-api","credentials":[{"accessKey":"%s","secretKey":"%s"}],
				  "actions":["Read:%s","Write:%s","List:%s"]}
				]}
				""".formatted(ROOT_KEY, ROOT_SECRET, APP_KEY, APP_SECRET, BUCKET, BUCKET, BUCKET);
		return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
			.withCopyToContainer(Transferable.of(identities), "/etc/s3.json")
			.withEnv("WEED_S3_SSE_KEY", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
			.withEnv("WEED_S3_SSE_KEK_PASSPHRASE", "test-only-kek-passphrase")
			.withEnv("WEED_JWT_FILER_SIGNING_KEY", "test-only-filer-signing-key")
			.withCommand("server", "-dir=/data", "-s3", "-s3.config=/etc/s3.json", "-s3.port=8333")
			.withExposedPorts(8333)
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(GenericContainer<?> storageContainer) {
		return registry -> {
			String endpoint = "http://" + storageContainer.getHost() + ":" + storageContainer.getMappedPort(8333);
			createEncryptedBucket(endpoint);
			registry.add("wellness.storage.endpoint", () -> endpoint);
			registry.add("wellness.storage.access-key", () -> APP_KEY);
			registry.add("wellness.storage.secret-key", () -> APP_SECRET);
		};
	}

	/** Same setup as deploy/local/storage/init-bucket.sh; retries while the S3 gateway starts. */
	static void createEncryptedBucket(String endpoint) {
		try (S3Client root = S3Client.builder()
			.endpointOverride(URI.create(endpoint))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ROOT_KEY, ROOT_SECRET)))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build()) {
			Instant deadline = Instant.now().plusSeconds(90);
			RuntimeException last = null;
			while (Instant.now().isBefore(deadline)) {
				try {
					if (root.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
						root.createBucket(b -> b.bucket(BUCKET));
					}
					root.putBucketEncryption(b -> b.bucket(BUCKET)
						.serverSideEncryptionConfiguration(c -> c.rules(r -> r.applyServerSideEncryptionByDefault(
								d -> d.sseAlgorithm(ServerSideEncryption.AES256)))));
					return;
				}
				catch (RuntimeException ex) {
					last = ex;
					try {
						Thread.sleep(1000);
					}
					catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw ex;
					}
				}
			}
			throw new IllegalStateException("Test storage did not become ready", last);
		}
	}

}
