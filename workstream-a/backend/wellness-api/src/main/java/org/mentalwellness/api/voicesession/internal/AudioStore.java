package org.mentalwellness.api.voicesession.internal;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypted audio objects. Every write requests SSE-S3 explicitly, so objects are encrypted even if
 * the bucket default were missing. Keys are {@code audio/<recording_id>}. Nothing here logs content.
 */
@Component
@EnableConfigurationProperties(AudioStoreProperties.class)
class AudioStore implements AutoCloseable {

	private final S3Client s3;

	private final String bucket;

	AudioStore(AudioStoreProperties properties) {
		this.bucket = properties.bucket();
		this.s3 = S3Client.builder()
			.endpointOverride(properties.endpoint())
			.region(Region.of(properties.region()))
			.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	static String keyFor(String recordingId) {
		return "audio/" + recordingId;
	}

	void put(String key, byte[] content, String contentType) {
		this.s3.putObject(b -> b.bucket(this.bucket)
			.key(key)
			.contentType(contentType)
			.contentLength((long) content.length)
			.serverSideEncryption(ServerSideEncryption.AES256), RequestBody.fromBytes(content));
	}

	byte[] get(String key) {
		return this.s3.getObjectAsBytes(b -> b.bucket(this.bucket).key(key)).asByteArray();
	}

	/** Server-side encryption reported for an object (for verification). */
	String encryption(String key) {
		return String.valueOf(this.s3.headObject(b -> b.bucket(this.bucket).key(key)).serverSideEncryptionAsString());
	}

	boolean exists(String key) {
		try {
			this.s3.headObject(b -> b.bucket(this.bucket).key(key));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
		catch (software.amazon.awssdk.services.s3.model.S3Exception ex) {
			if (ex.statusCode() == 404) {
				return false;
			}
			throw ex;
		}
	}

	/** Idempotent: deleting a missing object succeeds. */
	void delete(String key) {
		this.s3.deleteObject(b -> b.bucket(this.bucket).key(key));
	}

	@Override
	public void close() {
		this.s3.close();
	}

}
