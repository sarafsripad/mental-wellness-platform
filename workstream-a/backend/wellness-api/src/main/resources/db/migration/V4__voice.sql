-- V4: voice-session module (slice S5). Session and recording metadata only; audio bytes
-- live in encrypted object storage under random keys (ADR-005).

CREATE TABLE voice.session (
    session_id          text        PRIMARY KEY CHECK (session_id ~ '^sess_[a-f0-9]{32}$'),
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id    text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    language_tag        text        NOT NULL CHECK (language_tag IN ('en-IN', 'kn-IN')),
    prompt_set_id       text        NOT NULL CHECK (prompt_set_id ~ '^[a-z0-9-]{3,64}$'),
    prompt_set_version  text        NOT NULL CHECK (prompt_set_version ~ '^\d+\.\d+\.\d+$'),
    captured_at         timestamptz NOT NULL,
    device_class        text        NOT NULL CHECK (device_class IN ('ANDROID_PHONE', 'IOS_PHONE', 'TABLET', 'UNKNOWN')),
    app_version         text        NOT NULL CHECK (app_version ~ '^\d+\.\d+\.\d+$'),
    os_family           text        NOT NULL CHECK (os_family IN ('ANDROID', 'IOS')),
    data_origin         text        NOT NULL CHECK (data_origin IN ('SYNTHETIC', 'AUTHORIZED_TEST_AUDIO', 'PARTICIPANT')),
    created_by_subject  text        NOT NULL CHECK (created_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    cancelled_at        timestamptz
);
CREATE INDEX session_consent_idx ON voice.session (consent_event_id);
CREATE INDEX session_participant_idx ON voice.session (participant_ref);

CREATE TABLE voice.recording (
    recording_id        text        PRIMARY KEY CHECK (recording_id ~ '^rec_[a-f0-9]{32}$'),
    session_id          text        NOT NULL REFERENCES voice.session,
    prompt_id           text        NOT NULL CHECK (prompt_id ~ '^[a-z0-9-]{3,64}$'),
    format              text        NOT NULL CHECK (format IN ('WAV_PCM_S16LE', 'FLAC')),
    sampling_rate_hz    integer     NOT NULL CHECK (sampling_rate_hz IN (16000, 22050, 44100, 48000)),
    channels            integer     NOT NULL CHECK (channels = 1),
    duration_seconds    numeric(8,3) NOT NULL CHECK (duration_seconds BETWEEN 3 AND 180),
    size_bytes          bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 26214400),
    sha256              text        NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    recorded_at         timestamptz NOT NULL,
    prescreen_peak_dbfs numeric(6,2) CHECK (prescreen_peak_dbfs <= 0),
    prescreen_clipping  boolean,
    prescreen_low_level boolean,
    upload_status       text        NOT NULL DEFAULT 'AWAITING_CONTENT'
                                    CHECK (upload_status IN ('AWAITING_CONTENT', 'STORED', 'REJECTED', 'DELETED')),
    -- Random storage key (audio/<recording_id>); never derived from participant data.
    object_key          text        CHECK (object_key ~ '^audio/rec_[a-f0-9]{32}$'),
    stored_at           timestamptz,
    delete_pending      boolean     NOT NULL DEFAULT false,
    deleted_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (upload_status <> 'STORED' OR (object_key IS NOT NULL AND stored_at IS NOT NULL))
);
CREATE INDEX recording_session_idx ON voice.recording (session_id);
CREATE INDEX recording_delete_pending_idx ON voice.recording (recording_id) WHERE delete_pending;
