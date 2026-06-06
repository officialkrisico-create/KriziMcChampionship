-- ============================================================
-- KMC Achievement Data Migration: V1 → V2
-- ============================================================
-- V1 source tables (in KMCCore's kmccore.db):
--   player_achievements (uuid TEXT, achievement_id TEXT, unlocked_at BIGINT)
--   achievement_progress (uuid TEXT, achievement_id TEXT, progress INT)
--
-- V2 target tables (in kmc-storage's database, see SQLiteMigrationRunner migration2):
--   player_achievements (id INTEGER PK AUTOINCREMENT,
--                        player_uuid TEXT, achievement_id TEXT,
--                        event_number INTEGER, unlocked_at TEXT,
--                        UNIQUE(player_uuid, achievement_id))
--   achievement_progress (player_uuid TEXT, achievement_id TEXT,
--                         progress INTEGER, target INTEGER,
--                         PRIMARY KEY(player_uuid, achievement_id))
--
-- Key differences:
--   1. V1 uses column name "uuid"; V2 uses "player_uuid".
--   2. V1 stores unlocked_at as a BIGINT (epoch ms); V2 stores it as TEXT (ISO-8601).
--   3. V2 player_achievements has an auto-increment "id" and an "event_number" column
--      (which has no direct V1 equivalent — set to 0 as a placeholder).
--   4. V2 achievement_progress has a "target" column (the goal threshold) which
--      is not tracked by V1. Default to 1 (meaning the progress value itself
--      indicates completion when progress >= target).
--
-- Manual steps required before running:
--   1. Ensure both databases are accessible in the same SQLite process, or
--      copy V1 data to temp tables in the V2 database using ATTACH DATABASE.
--   2. Set the correct @event_number below for the current tournament event.
--   3. Run inside a transaction so partial failures can be rolled back.
--   4. After migration, verify row counts match between V1 and V2.
--   5. Achievements that existed in V1 but were removed from V2's catalog
--      will still be inserted (orphan rows) — review and prune manually.
--
-- Usage (run against the V2 database after ATTACHing V1):
--   ATTACH DATABASE '/path/to/kmccore.db' AS v1;
--   BEGIN;
--   [run this script]
--   COMMIT;
-- ============================================================

-- Step 1: Migrate unlocked achievements
-- V1 unlocked_at is epoch milliseconds (BIGINT).
-- V2 unlocked_at is TEXT ISO-8601. We convert via datetime().
-- epoch_ms / 1000 → epoch_seconds → datetime(..., 'unixepoch').
-- The event_number is set to 0 as a placeholder; update to the actual
-- event number for your tournament if known.

INSERT OR IGNORE INTO player_achievements
    (player_uuid, achievement_id, event_number, unlocked_at)
SELECT
    pa.uuid                                              AS player_uuid,
    pa.achievement_id                                    AS achievement_id,
    0                                                    AS event_number,
    datetime(pa.unlocked_at / 1000, 'unixepoch')         AS unlocked_at
FROM v1.player_achievements AS pa;

-- Step 2: Migrate achievement progress counters
-- V1 achievement_progress has no "target" column.
-- We default target to 1. For achievements that have a known threshold
-- (e.g. "win 5 games"), the target must be updated manually after migration.
-- See AchievementRegistry for the correct threshold per achievement_id.

INSERT OR REPLACE INTO achievement_progress
    (player_uuid, achievement_id, progress, target)
SELECT
    ap.uuid              AS player_uuid,
    ap.achievement_id    AS achievement_id,
    ap.progress          AS progress,
    1                    AS target  -- placeholder; update per achievement_id manually
FROM v1.achievement_progress AS ap;

-- Step 3 (manual — run after import):
-- Update targets for known incremental achievements.
-- Example (adjust thresholds to match your AchievementRegistry):
--
--   UPDATE achievement_progress SET target = 10  WHERE achievement_id = 'veteran';
--   UPDATE achievement_progress SET target = 50  WHERE achievement_id = 'regular';
--   UPDATE achievement_progress SET target = 25  WHERE achievement_id = 'killer';
--   UPDATE achievement_progress SET target = 3   WHERE achievement_id = 'hat_trick';
--
-- Consult AchievementRegistry.buildCatalog() for authoritative thresholds.

-- Step 4 (manual): verify migration
--
--   SELECT COUNT(*) FROM v1.player_achievements;         -- expected V1 count
--   SELECT COUNT(*) FROM player_achievements;            -- should match or be >= (IGNORE duplicates)
--   SELECT COUNT(*) FROM v1.achievement_progress;
--   SELECT COUNT(*) FROM achievement_progress;
--
-- Any row with a V1 achievement_id not present in V2's AchievementRegistry is
-- an orphan and can be deleted:
--   DELETE FROM player_achievements WHERE achievement_id NOT IN (<V2_IDS>);
--   DELETE FROM achievement_progress WHERE achievement_id NOT IN (<V2_IDS>);
