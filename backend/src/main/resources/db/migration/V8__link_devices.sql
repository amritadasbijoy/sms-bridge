-- "Add another phone": the browser's QR code carries a one-time secret that never reaches the
-- server. The new phone proves it scanned that QR code with HMAC-SHA256(secret, its key
-- fingerprint); the browser checks the proof against the keys listed here and approves the phone.
ALTER TABLE devices ADD COLUMN link_proof BYTEA;
