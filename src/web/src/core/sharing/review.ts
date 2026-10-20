// A moderator's review of a case (uc.moderation.review-a-case steps 3-5; decision 6 of cr-moderation-review-and-measures):
// the share viewer renders the held snapshot with the GUEST viewer, and only its data source changes. While a review is
// active, every guest read the views make — a blob's chunk URLs (`shareChunkUrls`), an application's listing under the
// link (Memories' `listShareMedia`, Depot's `listShareFiles`) — goes to the moderation reads instead (`/api/moderation/
// cases/{caseId}/…`, behind the role and a fresh key proof over the case), and never to a guest endpoint: a review spends
// no link's budget, needs no password, and outlives a revoke (`ShareReview.guard.test.ts` holds that no guest endpoint and
// no scope client is called).
//
// One review per page: the review route is a page of its own (`/s/<caseId>#moderation`), so the active review is a module
// slot, set when its page mounts and cleared when it leaves ([beginReview]). The keys the review opens never come here:
// this holds the case id and the two read paths, nothing else.

/** The proof headers a key-proved moderation read carries (`features/moderation/proof.ts`). */
export type ReviewProof = { xVeyqModerationChallenge: string; xVeyqModerationSignature: string }

/** The active review's data source. */
export interface ShareReview {
  readonly caseId: string
  /** The held blob's chunk URLs in chunk order (`presignCaseBlobs`, batched) — the review's `resolveShareBlob`. */
  chunkUrls(blobId: string): Promise<string[]>
  /** A fresh key proof for [operationId] over this case — what an application's held listing carries. */
  proof(operationId: string): Promise<ReviewProof>
}

let active: ShareReview | null = null

/** The review the page is showing, or null on every guest's page. */
export const activeReview = (): ShareReview | null => active

/** Make [review] the page's data source; the returned function ends it (the page's unmount). */
export function beginReview(review: ShareReview): () => void {
  active = review
  return () => {
    if (active === review) active = null
  }
}
