-- Flix Town TV requests: customers can remove requests from My Requests (run once, after 2026-10-14-tv-requests.sql).
-- Removing only hides the row from that customer's list; Seerr, the admin queue and duplicate checks are unchanged.
ALTER TABLE tv_media_requests ADD COLUMN hidden_ts INT UNSIGNED NOT NULL DEFAULT 0;
