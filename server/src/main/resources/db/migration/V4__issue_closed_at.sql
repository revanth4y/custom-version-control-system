-- When an issue was closed.
--
-- updated_at moves on any edit, so it is not a closure time: an issue closed in
-- January and retitled in June has an updated_at of June. There is no event log
-- to recover the real moment from, which is why this is persisted rather than
-- derived — it is a fact about the past that cannot be recomputed once lost.
--
-- Nullable, and deliberately not backfilled. Issues closed before this column
-- existed have no recorded closure time, and inventing one would put a
-- fabricated date into the analytics this column exists to keep honest. They
-- stay NULL and are reported as closed-but-undated.

ALTER TABLE issues ADD COLUMN closed_at TIMESTAMPTZ;
