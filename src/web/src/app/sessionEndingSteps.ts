// What an application does before the session ending loads the sign-in page (uc.account.sign-out § The ending): the one
// step the new document cannot take, because it lives OUTSIDE this page - Memories' music frame forgets the vendor's
// sign-in in the music host's storage (ADR 0131 decision 5, owner 2026-11-13), where the next person on a shared
// computer would otherwise start the service signed in as this one.
//
// An application registers its step through its lifecycle hooks (`onSessionEnding`, `core/applications/registry.ts`),
// never here directly; the ending (`app/sessionEnd.ts`) runs every step and waits for them - but never longer than
// ENDING_STEPS_BUDGET_MS: a sign-out is never held hostage by another origin's frame. A step that throws or hangs is
// given up, and the ending goes on. This module imports nothing, so the ending (which imports no feature and no router)
// and the registry can both reach it without a cycle.

/** Who started the ending: this tab (its own sign-out, a refresh the server refused, an account erasure), or another
 *  tab's announcement. A step that touches storage every tab shares does it once, in the tab that started it. */
export interface SessionEndingContext {
  startedHere: boolean
}

export type SessionEndingStep = (ctx: SessionEndingContext) => Promise<void>

/** The most the ending waits for all steps together before it loads the sign-in page anyway. */
export const ENDING_STEPS_BUDGET_MS = 2000

const steps = new Set<SessionEndingStep>()

/** Register [step] to run before every ending of this document; answers its removal. */
export function onSessionEnding(step: SessionEndingStep): () => void {
  steps.add(step)
  return () => void steps.delete(step)
}

/** Run every registered step, in parallel, and resolve when all have settled or the budget is spent. Never rejects. */
export async function runSessionEndingSteps(ctx: SessionEndingContext, budgetMs = ENDING_STEPS_BUDGET_MS): Promise<void> {
  if (steps.size === 0) return
  const all = Promise.allSettled([...steps].map((step) => Promise.resolve().then(() => step(ctx))))
  let timer: ReturnType<typeof setTimeout> | undefined
  const spent = new Promise<void>((resolve) => (timer = setTimeout(resolve, budgetMs)))
  await Promise.race([all, spent])
  clearTimeout(timer)
}
