-- Published app versions (APK hosted next to the server); phones are told about new ones at once.
CREATE TABLE app_releases (
    version_code    INT PRIMARY KEY,
    version_name    TEXT NOT NULL,
    url             TEXT NOT NULL,
    sha256          TEXT NOT NULL,
    required        BOOLEAN NOT NULL DEFAULT false,   -- older versions must update before syncing on
    notes           TEXT NOT NULL DEFAULT '',
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
