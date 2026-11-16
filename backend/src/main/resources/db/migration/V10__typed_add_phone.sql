-- "Add another phone" without a camera: the browser's pairing code also gets 8 digits that the new
-- phone types, the same kind of code as the sign-in page shows. Single use, expires with the code.
ALTER TABLE pairing_codes ADD COLUMN short_code TEXT;
CREATE INDEX pairing_codes_short ON pairing_codes (short_code) WHERE used_at IS NULL;
