// The sentences core's link dialog (`FolderShareDialog.vue`) says about WHAT is shared, as i18n keys: the dialog is the
// platform's one READ / READ_WRITE link manager, written for a folder, and a kind with another noun — Depot's single file —
// hands its own keys instead of the folder's `share.folder.*`. Every field defaults to the folder's key; a kind overrides
// only the sentences that would be wrong for it.

/** One i18n key per sentence. */
export interface ShareDialogCopy {
  /** The dialog's heading in its list view. */
  title?: string
  /** The paragraph above the links. */
  intro?: string
  /** The `READ_WRITE` role's name ("View + add files" for a folder). */
  roleContributor?: string
  /** What a `READ` link lets its guest do. */
  roleViewerHint?: string
  /** What a `READ_WRITE` link lets its guest do. */
  roleContributorHint?: string
  /** The question before a link is deleted for good. */
  deleteConfirm?: string
  /** The warning before a link is renewed. */
  reissueWarning?: string
}
