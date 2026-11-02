// The one editor model for a member's real name AND profile picture (ADR 0084 decision 6): both live sealed under value keys whose wraps `K_tenant` holds (ADR 0115)
// and both are written by their owner only, so ONE composable serves the two places that edit them - Settings > Your profile
// (`ProfilePanel`) and the member's OWN profile page (`PersonProfile`, shown only when the page is theirs). It holds the name
// fields and the picture choice (kept, replaced or removed) as a draft; the host's Save writes the name (`saveName`) and
// the picture (`savePicture`), each stating the tenant epoch it is sealed under and sealed once more under the re-opened
// key when a rotation answered `409 stale_epoch` (ADR 0115 decision 3). `K_tenant` stays in a closure, never in reactivity, and is zeroed when the editor goes away
// (its effect scope ends) or a new load replaces it (inbox #574).
import { computed, getCurrentScope, onScopeDispose, reactive, ref } from 'vue'
import {
  getMyProfile, openOwnNameKey, zeroOwnNameKey, sealName, openMemberName, sealAvatar, setMyAvatar, clearMyAvatar,
  type MemberProfile, type OwnNameKey, type SealedMemberName,
} from './profile'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { isStaleEpoch } from '../../spaces/scopes'
import { avatarUrlOf, loadTenantPeople, refreshPeopleDirectory } from '../../../core/people/directory'

type Draft = { kind: 'keep' } | { kind: 'set'; bytes: Uint8Array } | { kind: 'clear' }

export function useNameAndPicture() {
  const firstName = ref('')
  const lastName = ref('')
  const hasKey = ref(false)
  /** The signed-in member's own user id, from their key material; '' until loaded. */
  const meId = ref('')
  const serverHasPicture = ref(false)
  const draft = ref<Draft>({ kind: 'keep' })
  const dialogOpen = ref(false)
  const preview = useObjectUrl() // the draft picture's object URL; revoked on replace and on unmount
  // `K_tenant` + the address our name and picture are sealed at: outside any reactive proxy. Every use is synchronous
  // (`sealName`, `sealAvatar` before the PUT's await), so zeroing it between uses never pulls it from under one.
  let ownKey: OwnNameKey | null = null
  let disposed = false
  function release() {
    if (ownKey) zeroOwnNameKey(ownKey)
    ownKey = null
  }
  if (getCurrentScope()) {
    onScopeDispose(() => {
      disposed = true
      release()
    })
  }

  /** The picture to draw: the draft's preview, nothing for a removal, else the directory's cached one. */
  const pictureUrl = computed<string | null>(() => {
    if (draft.value.kind === 'set') return preview.url.value
    if (draft.value.kind === 'clear') return null
    return avatarUrlOf(meId.value)
  })
  const hasPicture = computed(() => (draft.value.kind === 'set' ? true : draft.value.kind === 'clear' ? false : serverHasPicture.value))
  const pictureChanged = computed(() => draft.value.kind !== 'keep')

  /** Read the key and fill the fields from an already-read profile (or read it). */
  async function load(profile?: MemberProfile): Promise<MemberProfile> {
    const p = profile ?? (await getMyProfile())
    const opened = await openOwnNameKey()
    if (disposed) {
      // The editor went away while the keys were opening: nobody will seal with them.
      if (opened) zeroOwnNameKey(opened)
      return p
    }
    release()
    ownKey = opened
    hasKey.value = ownKey !== null
    meId.value = ownKey?.address.userId ?? ''
    serverHasPicture.value = p.avatarRevision > 0
    if (ownKey) {
      // Its value key opens under the tenant's one key: every rotation re-wraps every value key under the new one (ADR 0115).
      const name = openMemberName(ownKey.epochs, ownKey.address, p.sealedName, p.wrappedNameValueKey)
      firstName.value = name?.firstName ?? ''
      lastName.value = name?.lastName ?? ''
      // Warm the directory so the member's own picture is in the cache (it fetches only a version it has not seen).
      void loadTenantPeople().catch(() => undefined)
    }
    return p
  }

  /** The name sealed as the fields hold it (an EMPTY name included - that is how a member removes it) with the tenant epoch
   *  its value key is wrapped under, or undefined without the key. Every call mints a fresh value key (ADR 0115). */
  function sealedName(): (SealedMemberName & { epoch: number }) | undefined {
    return ownKey
      ? { ...sealName(ownKey.epochs, ownKey.address, { firstName: firstName.value, lastName: lastName.value }), epoch: ownKey.epochs.epoch }
      : undefined
  }

  /** Re-open the tenant key after a `409 stale_epoch` — a rotation replaced it since the editor opened (ADR 0115
   *  decision 3). False when there is no tenant key any more. */
  async function reopen(): Promise<boolean> {
    const opened = await openOwnNameKey()
    if (disposed) {
      if (opened) zeroOwnNameKey(opened)
      return false
    }
    release()
    ownKey = opened
    hasKey.value = ownKey !== null
    return ownKey !== null
  }

  /** Run `write` with {@link sealedName}; on `409 stale_epoch` re-open the key, seal again and run it once more. */
  async function staleOnce<T>(write: () => Promise<T>): Promise<T> {
    try {
      return await write()
    } catch (e) {
      if (!(await isStaleEpoch(e)) || !(await reopen())) throw e
      return write()
    }
  }

  /** Write the name through `send` (the host's profile update, which may carry the username and the consent too): sealed
   *  as {@link sealedName} gives it, once more under the re-opened key after a `stale_epoch`. */
  function saveName<T>(send: (name: (SealedMemberName & { epoch: number }) | undefined) => Promise<T>): Promise<T> {
    return staleOnce(() => send(sealedName()))
  }

  function choose(bytes: Uint8Array) {
    draft.value = { kind: 'set', bytes }
    preview.set(new Blob([bytes as BlobPart], { type: 'image/webp' }))
    dialogOpen.value = false
  }
  function remove() {
    draft.value = { kind: 'clear' }
    preview.clear()
    dialogOpen.value = false
  }
  function discard() {
    draft.value = { kind: 'keep' }
    preview.clear()
  }

  /**
   * Write the picture draft: seal and PUT a chosen one, DELETE a removed one, nothing for a kept one. Afterwards the directory is
   * re-read so every avatar on screen follows the bumped version. Throws on failure (the draft stays for a retry).
   */
  async function savePicture(): Promise<void> {
    const d = draft.value
    if (d.kind === 'keep') return
    if (d.kind === 'set') {
      if (!ownKey) throw new Error('no_tenant_key')
      await staleOnce(() => {
        if (!ownKey) throw new Error('no_tenant_key')
        return setMyAvatar(sealAvatar(ownKey.epochs, ownKey.address, d.bytes), ownKey.epochs.epoch)
      })
    } else {
      await clearMyAvatar()
    }
    serverHasPicture.value = d.kind === 'set'
    await refreshPeopleDirectory()
    discard()
  }

  return reactive({
    firstName, lastName, hasKey, meId, hasPicture, pictureUrl, pictureChanged, dialogOpen, draftKind: computed(() => draft.value.kind),
    load, sealedName, saveName, choose, remove, discard, savePicture,
  })
}

export type NameAndPicture = ReturnType<typeof useNameAndPicture>
