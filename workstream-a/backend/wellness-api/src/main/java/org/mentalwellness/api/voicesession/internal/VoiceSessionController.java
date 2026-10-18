package org.mentalwellness.api.voicesession.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.AccessReasons;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Voice-session operations of wellness-api.v1.yaml. */
@RestController
@Validated
class VoiceSessionController {

	private final VoiceSessionService service;

	VoiceSessionController(VoiceSessionService service) {
		this.service = service;
	}

	@PostMapping("/v1/voice-sessions")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_voice:write')")
	ResponseEntity<VoiceApi.VoiceSession> create(@RequestHeader("Idempotency-Key") UUID key,
			@Valid @RequestBody VoiceApi.VoiceSessionCreate request) {
		var result = this.service.createSession(Actor.current(), key, request);
		return result.replayed() ? ResponseEntity.ok(result.body())
				: ResponseEntity.created(URI.create("/v1/voice-sessions/" + result.body().sessionId())).body(result.body());
	}

	@GetMapping("/v1/voice-sessions/{sessionId}")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'ADMIN') and hasAuthority('SCOPE_voice:read')")
	VoiceApi.VoiceSession get(@PathVariable @Pattern(regexp = VoiceApi.SESSION_ID) String sessionId,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		return this.service.getSession(actor, sessionId, AccessReasons.resolve(actor, reason).orElse(null));
	}

	@PostMapping("/v1/voice-sessions/{sessionId}/recordings")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_voice:write')")
	ResponseEntity<VoiceApi.Recording> register(@PathVariable @Pattern(regexp = VoiceApi.SESSION_ID) String sessionId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody VoiceApi.RecordingCreate request) {
		var result = this.service.registerRecording(Actor.current(), key, sessionId, request);
		return result.replayed() ? ResponseEntity.ok(result.body())
				: ResponseEntity
					.created(URI.create("/v1/voice-sessions/" + sessionId + "/recordings/" + result.body().recordingId()))
					.body(result.body());
	}

	@PutMapping(path = "/v1/voice-sessions/{sessionId}/recordings/{recordingId}/content",
			consumes = { "audio/wav", "audio/flac" })
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_voice:write')")
	VoiceApi.Recording upload(@PathVariable @Pattern(regexp = VoiceApi.SESSION_ID) String sessionId,
			@PathVariable @Pattern(regexp = VoiceApi.RECORDING_ID) String recordingId,
			@RequestHeader("X-Content-SHA256") @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
			HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > VoiceApi.MAX_BYTES) {
			throw new DomainException(ProblemCode.PAYLOAD_TOO_LARGE);
		}
		return this.service.upload(Actor.current(), sessionId, recordingId, request.getContentType(), sha256,
				readBounded(request.getInputStream()));
	}

	/** Reads at most {@link VoiceApi#MAX_BYTES}; larger bodies are refused without buffering them. */
	private static byte[] readBounded(InputStream in) throws IOException {
		byte[] content = in.readNBytes((int) VoiceApi.MAX_BYTES + 1);
		if (content.length > VoiceApi.MAX_BYTES) {
			throw new DomainException(ProblemCode.PAYLOAD_TOO_LARGE);
		}
		return content;
	}

}
