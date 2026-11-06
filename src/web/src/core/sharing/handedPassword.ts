// A share's password handed to its page by a bookmark of this instance (ADR 0120 decision 5): kept in this document's memory
// only, for one share id, and taken once — the share page enters it in its own password form when the link asks for one,
// and a wrong one leaves that form to the reader. Never in a URL, never in storage: a foreign link's password is shown to
// the member to copy instead, since no other origin can be handed anything.
const handed = new Map<string, string>()

/** Hand [password] to the next page of [shareId] this document opens. */
export function handSharePassword(shareId: string, password: string): void {
  handed.set(shareId, password)
}

/** The password handed for [shareId], once: taken, it is gone. Null when none was handed. */
export function takeHandedSharePassword(shareId: string): string | null {
  const password = handed.get(shareId) ?? null
  handed.delete(shareId)
  return password
}
