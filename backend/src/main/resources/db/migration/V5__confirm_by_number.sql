-- Number-matching fallback for pairing without a camera.

-- A waiting browser asked to be confirmed on a phone; the approving phone's random challenge.
ALTER TABLE devices ADD COLUMN confirm_requested_at TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN confirm_salt BYTEA;

-- An unconnected phone asks to join an account; the signed-in owner taps the matching number.
-- user_id is NULL for unknown usernames (the phone gets the same answer, so names cannot be probed).
CREATE TABLE connect_requests (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    number          INT NOT NULL,
    options         TEXT NOT NULL,              -- the three numbers shown to the owner, e.g. "12,35,91"
    secret_hash     BYTEA NOT NULL,             -- the phone polls with this secret
    status          TEXT NOT NULL DEFAULT 'pending',
    pairing_code    TEXT,                       -- handed to the phone once, then cleared
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX connect_requests_user ON connect_requests (user_id, status);
