/**
 * Memories' blob roles — its values of the contract's open `BlobRole` token (a string the owning application defines;
 * core stores and compares it, never interprets it: cr-files-application P3-S2). A medium's original and its two
 * derivative tiers, and a node's standalone cover. Each blob's `K_blob` wrap is sealed bound to its role (ADR 0072),
 * so this layer types every ref it builds with one of these, while the neutral blob transport takes any `string`.
 */
export type MemoryBlobRole = 'ORIGINAL' | 'THUMBNAIL' | 'PREVIEW' | 'COVER'
