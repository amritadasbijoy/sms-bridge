-- "I already have an account": a phone that made a temporary account by scanning moves into the
-- user's existing account. The approved browser of the temporary account starts the move; after
-- signing in to the old account with its password it names the target; the phone picks it up and
-- joins with a one-time pairing code. Rows are short-lived and deleted once used or expired.
CREATE TABLE account_moves (
    id              UUID PRIMARY KEY,
    token_hash      BYTEA NOT NULL UNIQUE,
    from_user       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_user         UUID REFERENCES users (id) ON DELETE CASCADE,
    pairing_code    TEXT,                      -- for the phone; the account it joins is to_user
    link_secret     BYTEA,                     -- the phone proves it got this move (like an add-phone QR)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_moves_from ON account_moves (from_user);
