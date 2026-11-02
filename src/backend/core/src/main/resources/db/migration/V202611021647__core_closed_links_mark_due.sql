-- ADR 0114 decision 1: every close of a READ or READ_WRITE link marks every node of its root's subtree due, so its
-- holder reads nothing added after the close. V202611021600 cleared the keys of the links switched off before it but
-- marked nothing: until a rotation, their holders kept reading - with a dump - what is added under their roots, since a
-- rotation before ADR 0114 re-wrapped its new key under a switched-off link too, and a deleted link's holder holds the
-- root's key as it stood at the delete. This marks them, on the safe side: every node, live and trashed, of every scope
-- holding a switched-off or deleted READ or READ_WRITE link whose root still exists (a node of the scope outside such a
-- subtree is re-keyed for nothing; a root purged since leaves nothing to protect). A WRITE_ONLY link's close marks
-- nothing (decision 2). A link whose end date has passed is not marked here: it is still enabled and unswept, and the
-- end-date sweep closes it and marks its subtree on its first run after the upgrade (decision 6) - as it does every
-- expired link. V202611021400 clears no key a closed link held and marks every node that lagged its scope; nothing of
-- it needs a mark here.
UPDATE veyq_node SET rekey_due = TRUE
    WHERE scope_id IN (
        SELECT s.scope_id FROM veyq_share s
        WHERE s.role IN ('READ', 'READ_WRITE')
          AND (s.enabled = FALSE OR s.deleted = TRUE)
          AND EXISTS (SELECT 1 FROM veyq_node r WHERE r.id = s.node_id)
    );
