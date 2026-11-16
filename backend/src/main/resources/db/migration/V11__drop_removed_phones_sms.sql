-- Since 0.10.3 removing a phone deletes its messages from the server. Phones removed before that
-- kept theirs; remove those too, as the user asked. The phones themselves keep their own copies.
DELETE FROM sync_events e USING devices d
WHERE e.device_id = d.id AND d.revoked_at IS NOT NULL AND d.platform <> 'web';
