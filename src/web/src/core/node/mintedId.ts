// A create's id is minted by the client (ADR 0072 Decision 3): every ciphertext of the create is sealed bound to it,
// so the server stores it verbatim or refuses the create (`id_taken`). This is the client's half of that contract.

/** Return the id the client minted, refusing a response that names another — a substituted id would leave the
 *  caller holding an object its own seals do not open at, which is worse than a failed create. */
export function expectMintedId(minted: string, echoed: string): string {
  if (echoed !== minted) throw new Error('id_substituted')
  return minted
}
