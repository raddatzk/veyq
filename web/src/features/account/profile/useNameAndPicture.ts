// The one editor model for a member's real name AND profile picture (ADR 0084 decision 6): both live sealed under `K_tenant`
// and both are written by their owner only, so ONE composable serves the two places that edit them - Settings > Your profile
// (`ProfilePanel`) and the member's OWN profile page (`PersonProfile`, shown only when the page is theirs). It holds the name
// fields and the picture choice (kept, replaced or removed) as a draft; the host's Save seals the name (`sealedName`) and
// writes the picture (`savePicture`). `K_tenant` stays in a closure, never in reactivity.
import { computed, reactive, ref } from 'vue'
import { getMyProfile, openOwnNameKey, sealName, unsealName, sealAvatar, setMyAvatar, clearMyAvatar, type MemberProfile } from './profile'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
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
  // `K_tenant` + the address our name and picture are sealed at: outside any reactive proxy.
  let ownKey: Awaited<ReturnType<typeof openOwnNameKey>> = null

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
    ownKey = await openOwnNameKey()
    hasKey.value = ownKey !== null
    meId.value = ownKey?.address.userId ?? ''
    serverHasPicture.value = p.avatarRevision > 0
    if (ownKey) {
      const name = unsealName(ownKey.key, ownKey.address, p.sealedName)
      firstName.value = name?.firstName ?? ''
      lastName.value = name?.lastName ?? ''
      // Warm the directory so the member's own picture is in the cache (it fetches only a version it has not seen).
      void loadTenantPeople().catch(() => undefined)
    }
    return p
  }

  /** The name sealed as the fields hold it (an EMPTY name included - that is how a member removes it), or undefined without the key. */
  function sealedName(): string | undefined {
    return ownKey ? sealName(ownKey.key, ownKey.address, { firstName: firstName.value, lastName: lastName.value }) : undefined
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
      await setMyAvatar(sealAvatar(ownKey.key, ownKey.address, d.bytes))
    } else {
      await clearMyAvatar()
    }
    serverHasPicture.value = d.kind === 'set'
    await refreshPeopleDirectory()
    discard()
  }

  return reactive({
    firstName, lastName, hasKey, meId, hasPicture, pictureUrl, pictureChanged, dialogOpen, draftKind: computed(() => draft.value.kind),
    load, sealedName, choose, remove, discard, savePicture,
  })
}

export type NameAndPicture = ReturnType<typeof useNameAndPicture>
