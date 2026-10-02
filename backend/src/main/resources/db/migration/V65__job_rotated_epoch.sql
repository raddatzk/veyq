-- A queued scope rotation completes only on its lease holder's own commit (uc.spaces.complete-queued-rotation).
--
-- The epoch a rotation commit created, written onto every scope-rotation job of that scope leased to the committer,
-- in the commit's transaction. The completion refuses a scope-rotation job on which it is null: without it a job
-- went DONE on the worker's word and closed the events it carried with no rotation behind them. Never cleared on a
-- reclaim: every holder's lease begins after every event the job carries. A plain integer, no foreign key; null on
-- every other tag and on every job before this one.
ALTER TABLE veyq_job ADD COLUMN rotated_epoch INT NULL;
