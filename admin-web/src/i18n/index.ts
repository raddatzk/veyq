import { createI18n } from 'vue-i18n'

// `admin-web`'s own catalog (EN + DE). Lifted verbatim from `web/src/i18n/index.ts` for the strings that moved
// with their views, so the moved components need no string changes and the two catalogs cannot disagree about
// a shared sentence — they simply no longer share one. Keys the app surface still uses stayed there; keys only
// this surface uses were deleted there. No shared i18n package: ADR 0065 keeps everything but the crypto
// independent per package on purpose.
export type SupportedLocale = 'en' | 'de'

const en = {
  "common": {
    "email": "Email",
    "password": "Password",
    "cancel": "Cancel",
    "create": "Create",
    "done": "Done"
  },
  "tenant": {
    "create": "Create tenant",
    "name": "Tenant name",
    "domain": "Domain",
    "domainHint": "The web address this tenant is reached at, e.g. family.example.com.",
    "ownerEmail": "Owner email",
    "ownerEmailNew": "Who should own this tenant? (email)",
    "domainTaken": "That domain is already in use by another tenant.",
    "createFailed": "Could not create the tenant. Please try again.",
    "created": "Tenant created.",
    "inviteReissued": "Invite re-issued.",
    "statusPending": "Pending",
    "statusActive": "Active",
    "resend": "Re-issue invite",
    "revoke": "Revoke",
    "reassignSubmit": "Reassign invite",
    "resent": "Invite re-issued.",
    "reassigned": "Invite reassigned.",
    "revokeConfirm": "Revoke this pending tenant? Its invite stops working.",
    "revokeConfirmTitle": "Revoke this pending tenant",
    "statusSuspended": "Suspended",
    "suspend": "Take offline",
    "reactivate": "Bring back online",
    "suspendConfirm": "Take this tenant offline? Nobody — including its members — can reach it until you bring it back. Nothing inside it is changed or deleted.",
    "suspendConfirmTitle": "Take this tenant offline",
    "suspended": "Tenant taken offline.",
    "reactivated": "Tenant is back online.",
    "suspendedHint": "This tenant is offline. Every request to its address is refused until you bring it back; its content and members are untouched.",
    "changeDomainTitle": "Move this tenant to a different address",
    "changeDomainSubmit": "Move",
    "changeDomainHint": "The tenant answers on the new address and stops answering on the old one. Members have to use the new address from then on.",
    "changeDomainConfirm": "Move this tenant to {domain}? The old address stops working straight away.",
    "changeDomainConfirmTitle": "Move this tenant to a new address",
    "domainChanged": "Tenant moved to its new address.",
    "domainInvalid": "That is not a valid web address. Use a form like family.example.com.",
    "domainNotMovable": "An offline tenant cannot be moved. Bring it back online first.",
    "storageUsed": "Storage in use:",
    "quota": "Quota:",
    "quotaNone": "none",
    "quotaInput": "Storage quota (GiB)",
    "quotaHint": "The most this tenant may keep on this server. Uploads that would go over it are refused; nothing already stored is removed, and trash counts until it is emptied. Without a quota the disk is the limit.",
    "quotaSave": "Save quota",
    "quotaClear": "Remove quota",
    "quotaSaved": "Quota saved.",
    "quotaCleared": "Quota removed.",
    "quotaInvalid": "Enter a number of GiB, 0 or more.",
    "actionFailed": "That action failed. Please try again.",
    "loading": "Loading tenants…",
    "loadFailed": "Could not load tenants. Please try again.",
    "notAuthorized": "Only the server owner can manage tenants.",
    "empty": "No tenants yet. Create the first one."
  },
  "release": {
    "navTitle": "Release",
    "sub": "Which build of veyq this server is running — the version to name if an update ever has to be undone.",
    "loading": "Reading the release version…",
    "loadFailed": "Couldn’t read the release version.",
    "notOwner": "Only the server owner can see which release this instance runs.",
    "running": "Running release",
    "unstamped": "This build carries no release stamp. It was never published — a local build, or a development run — so there is no version to name, and none is invented here.",
    "rollbackHint": "Pin this version in your deployment rather than a tag that moves: it is the one you can go back to, and going back means restoring the backup you took before the update."
  },
  "store": {
    "navTitle": "Object store",
    "sub": "Whether this server has brought its object store from running to usable — media cannot be uploaded or shown until it has.",
    "loading": "Reading the object store's state…",
    "loadFailed": "Couldn’t read the object store's state.",
    "notOwner": "Only the server owner can see the state of this instance's object store.",
    "stateLabel": "Reported state",
    "notAttempted": "This server does not set its object store up, and is not meant to. The store is configured as one you brought yourself, so its bucket, key and CORS rule are yours to manage — nothing is missing here.",
    "inProgress": "Setting the object store up. This normally takes a few seconds after a start; uploads may fail until it finishes. Reload to see where it got to.",
    "provisioned": "The object store is ready. Its bucket, access key and browser rule are in place, so media can be uploaded and shown.",
    "failed": "The object store could not be set up, and this server has stopped trying. Media will not work until the store is reachable again and this server is restarted. The server log names the step that failed."
  },
  "setup": {
    "title": "Set up this instance",
    "sub": "Create the first owner account. All keys are generated on this device — the server only ever sees ciphertext.",
    "submit": "Confirm",
    "submitting": "Confirming…",
    "failed": "Setup failed. Please try again.",
    "kitTitle": "Save your Emergency Kit",
    "kitSub": "Your Secret Key recovers your account if you ever forget your password. Save the kit somewhere safe before continuing.",
    "kitProceed": "Continue to sign in"
  },
  "members": {
    "copied": "Copied",
    "copyLink": "Copy link",
    "copyLinkIntro": "A tenant has no email set up until its owner configures it, so this first invitation is yours to pass on. Copy the link and send it to them."
  },
  "kit": {
    "download": "Download Emergency Kit (PDF)",
    "downloaded": "Emergency Kit downloaded.",
    "downloadFailed": "Could not create the Emergency Kit. Please try again.",
    "backupDownloadConfirm": "I have saved my Emergency Kit somewhere safe.",
    "pdfTitle": "veyq Emergency Kit",
    "pdfHeading": "veyq — Emergency Kit",
    "pdfKeepSafe": "Keep this safe. With your Secret Key + your password you can always sign in again.",
    "pdfEmail": "Email",
    "pdfSecretKey": "Secret Key",
    "pdfScanHint": "Scan this code, or import this PDF, to fill in your email + Secret Key on a new device."
  },
  "password": {
    "minLength": "Use at least 10 characters.",
    "strong": "Nice and long — that is a strong password.",
    "pwnedCheck": "Check this password against known data breaches",
    "pwnedCheckNote": "Only the first 5 characters of a hash of the password go to haveibeenpwned.com — never the password itself.",
    "pwnedWarning": "This password appeared in {count} known data breaches. Choose a different one, or press the button again to use it anyway.",
    "pwnedUnavailable": "The breach check could not run. Press the button again to continue without it."
  },
  "admin": {
    "title": "Server administration",
    "sub": "The instance itself: its tenants and the release it runs.",
    "signOut": "Sign out",
    "tenants": "Tenants",
    "server": "Server",
    "storageOfQuota": "{used} of {quota}",
    "storageNoQuota": "{used}, no quota",
    "forbidden": "This account is not the Server Owner.",
    "forbiddenSub": "Server administration is reserved for the account that set this instance up. Nothing here is available to any other account — the server refuses each of these calls in its own right, not just this page.",
    "unreachable": "This instance can’t be reached right now. Please try again.",
    "signIn": "Sign in",
    "signingIn": "Signing in…",
    "signInSub": "Sign in as the Server Owner to administer this instance.",
    "signInFailed": "Sign-in failed. Check the email and password.",
    "noTenants": "No tenants yet. Create the first one.",
    "loading": "Loading…"
  }
}

const de: typeof en = {
  "common": {
    "email": "E-Mail",
    "password": "Passwort",
    "cancel": "Abbrechen",
    "create": "Erstellen",
    "done": "Fertig"
  },
  "tenant": {
    "create": "Tenant anlegen",
    "name": "Tenant-Name",
    "domain": "Domain",
    "domainHint": "Die Web-Adresse, unter der dieser Tenant erreichbar ist, z. B. family.example.com.",
    "ownerEmail": "E-Mail der besitzenden Person",
    "ownerEmailNew": "Wem soll der Tenant gehören? (E-Mail)",
    "domainTaken": "Diese Domain wird bereits von einem anderen Tenant verwendet.",
    "createFailed": "Tenant konnte nicht angelegt werden. Bitte erneut versuchen.",
    "created": "Tenant angelegt.",
    "inviteReissued": "Einladung neu ausgestellt.",
    "statusPending": "Ausstehend",
    "statusActive": "Aktiv",
    "resend": "Einladung neu ausstellen",
    "revoke": "Widerrufen",
    "reassignSubmit": "Einladung neu zuweisen",
    "resent": "Einladung neu ausgestellt.",
    "reassigned": "Einladung neu zugewiesen.",
    "revokeConfirm": "Diesen ausstehenden Tenant widerrufen? Seine Einladung wird ungültig.",
    "revokeConfirmTitle": "Diesen ausstehenden Tenant widerrufen",
    "statusSuspended": "Offline",
    "suspend": "Offline nehmen",
    "reactivate": "Wieder online nehmen",
    "suspendConfirm": "Diesen Tenant offline nehmen? Niemand — auch seine Mitglieder nicht — kommt hinein, bis du ihn wieder online nimmst. Nichts darin wird geändert oder gelöscht.",
    "suspendConfirmTitle": "Diesen Tenant offline nehmen",
    "suspended": "Tenant ist offline.",
    "reactivated": "Tenant ist wieder online.",
    "suspendedHint": "Dieser Tenant ist offline. Jede Anfrage an seine Adresse wird abgewiesen, bis du ihn wieder online nimmst; Inhalte und Mitglieder bleiben unberührt.",
    "changeDomainTitle": "Diesen Tenant auf eine andere Adresse umziehen",
    "changeDomainSubmit": "Umziehen",
    "changeDomainHint": "Der Tenant ist danach unter der neuen Adresse erreichbar und unter der alten nicht mehr. Mitglieder müssen ab dann die neue Adresse verwenden.",
    "changeDomainConfirm": "Diesen Tenant auf {domain} umziehen? Die alte Adresse funktioniert sofort nicht mehr.",
    "changeDomainConfirmTitle": "Diesen Tenant auf eine neue Adresse umziehen",
    "domainChanged": "Tenant ist umgezogen.",
    "domainInvalid": "Das ist keine gültige Web-Adresse. Verwende eine Form wie family.example.com.",
    "domainNotMovable": "Ein Tenant, der offline ist, kann nicht umziehen. Nimm ihn zuerst wieder online.",
    "storageUsed": "Belegter Speicher:",
    "quota": "Speichergrenze:",
    "quotaNone": "keine",
    "quotaInput": "Speichergrenze (GiB)",
    "quotaHint": "So viel darf dieser Tenant höchstens auf diesem Server ablegen. Uploads, die darüber gingen, werden abgewiesen; bereits Gespeichertes wird nicht entfernt, und der Papierkorb zählt mit, bis er geleert ist. Ohne Grenze ist die Festplatte die Grenze.",
    "quotaSave": "Grenze speichern",
    "quotaClear": "Grenze entfernen",
    "quotaSaved": "Speichergrenze gespeichert.",
    "quotaCleared": "Speichergrenze entfernt.",
    "quotaInvalid": "Gib eine Zahl in GiB ein, 0 oder mehr.",
    "actionFailed": "Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.",
    "loading": "Tenants werden geladen…",
    "loadFailed": "Tenants konnten nicht geladen werden. Bitte erneut versuchen.",
    "notAuthorized": "Nur wer den Server besitzt, kann Tenants verwalten.",
    "empty": "Noch keine Tenants. Lege den ersten an."
  },
  "release": {
    "navTitle": "Release",
    "sub": "Welchen Stand von veyq dieser Server ausführt — die Version, die du benennen kannst, wenn ein Update rückgängig gemacht werden muss.",
    "loading": "Release-Version wird gelesen…",
    "loadFailed": "Die Release-Version konnte nicht gelesen werden.",
    "notOwner": "Nur die Besitzperson des Servers kann sehen, welches Release diese Instanz ausführt.",
    "running": "Laufendes Release",
    "unstamped": "Dieser Stand trägt keine Release-Kennung. Er wurde nie veröffentlicht — ein lokaler Build oder ein Entwicklungslauf —, es gibt also keine Version zu benennen, und hier wird keine erfunden.",
    "rollbackHint": "Trage diese Version fest in dein Deployment ein statt eines Tags, der sich bewegt: Sie ist die, zu der du zurückkehren kannst — und Zurückkehren heißt, das Backup einzuspielen, das du vor dem Update gemacht hast."
  },
  "store": {
    "navTitle": "Objektspeicher",
    "sub": "Ob dieser Server seinen Objektspeicher von laufend auf nutzbar gebracht hat — ohne das lassen sich keine Medien hochladen oder anzeigen.",
    "loading": "Zustand des Objektspeichers wird gelesen…",
    "loadFailed": "Der Zustand des Objektspeichers konnte nicht gelesen werden.",
    "notOwner": "Nur der Server-Eigentümer sieht den Zustand des Objektspeichers dieser Instanz.",
    "stateLabel": "Gemeldeter Zustand",
    "notAttempted": "Dieser Server richtet seinen Objektspeicher nicht ein und soll das auch nicht. Der Speicher ist als einer konfiguriert, den du selbst mitbringst — Bucket, Zugriffsschlüssel und Browser-Regel liegen bei dir. Hier fehlt nichts.",
    "inProgress": "Der Objektspeicher wird eingerichtet. Das dauert nach einem Start normalerweise ein paar Sekunden; bis dahin können Uploads fehlschlagen. Lade neu, um den Stand zu sehen.",
    "provisioned": "Der Objektspeicher ist bereit. Bucket, Zugriffsschlüssel und Browser-Regel sind vorhanden, Medien lassen sich hochladen und anzeigen.",
    "failed": "Der Objektspeicher konnte nicht eingerichtet werden, und dieser Server hat es aufgegeben. Medien funktionieren erst wieder, wenn der Speicher erreichbar ist und dieser Server neu gestartet wurde. Das Server-Log nennt den fehlgeschlagenen Schritt."
  },
  "setup": {
    "title": "Diese Instanz einrichten",
    "sub": "Lege das erste Konto an, dem diese Instanz gehört. Alle Schlüssel entstehen auf diesem Gerät — der Server sieht nur Chiffretext.",
    "submit": "Bestätigen",
    "submitting": "Wird bestätigt…",
    "failed": "Einrichtung fehlgeschlagen. Bitte erneut versuchen.",
    "kitTitle": "Notfall-Kit sichern",
    "kitSub": "Dein Secret Key stellt dein Konto wieder her, falls du dein Passwort vergisst. Sichere das Kit, bevor du fortfährst.",
    "kitProceed": "Weiter zur Anmeldung"
  },
  "members": {
    "copied": "Kopiert",
    "copyLink": "Link kopieren",
    "copyLinkIntro": "Ein Bereich hat noch keine E-Mail eingerichtet, solange seine Besitzerin oder sein Besitzer das nicht getan hat — diese erste Einladung gibst du deshalb selbst weiter. Kopiere den Link und schick ihn."
  },
  "kit": {
    "download": "Notfall-Kit herunterladen (PDF)",
    "downloaded": "Notfall-Kit heruntergeladen.",
    "downloadFailed": "Notfall-Kit konnte nicht erstellt werden. Bitte erneut versuchen.",
    "backupDownloadConfirm": "Ich habe mein Notfall-Kit sicher gespeichert.",
    "pdfTitle": "veyq-Notfall-Kit",
    "pdfHeading": "veyq — Notfall-Kit",
    "pdfKeepSafe": "Bewahre es sicher auf. Mit deinem Secret Key und deinem Passwort kannst du dich jederzeit wieder anmelden.",
    "pdfEmail": "E-Mail",
    "pdfSecretKey": "Secret Key",
    "pdfScanHint": "Scanne diesen Code oder importiere dieses PDF, um auf einem neuen Gerät deine E-Mail und deinen Secret Key einzutragen."
  },
  "password": {
    "minLength": "Mindestens 10 Zeichen verwenden.",
    "strong": "Schön lang — ein starkes Passwort.",
    "pwnedCheck": "Passwort mit bekannten Datenlecks abgleichen",
    "pwnedCheckNote": "Nur die ersten 5 Zeichen eines Hashwerts des Passworts gehen an haveibeenpwned.com — nie das Passwort selbst.",
    "pwnedWarning": "Dieses Passwort tauchte in {count} bekannten Datenlecks auf. Wähle ein anderes oder klicke erneut, um es trotzdem zu verwenden.",
    "pwnedUnavailable": "Die Prüfung auf Datenlecks konnte nicht laufen. Klicke erneut, um ohne sie fortzufahren."
  },
  "admin": {
    "title": "Serververwaltung",
    "sub": "Die Instanz selbst: ihre Tenants und die laufende Version.",
    "signOut": "Abmelden",
    "tenants": "Tenants",
    "server": "Server",
    "storageOfQuota": "{used} von {quota}",
    "storageNoQuota": "{used}, keine Grenze",
    "forbidden": "Dieses Konto ist nicht der Server-Owner.",
    "forbiddenSub": "Die Serververwaltung ist dem Konto vorbehalten, das diese Instanz eingerichtet hat. Für jedes andere Konto ist hier nichts verfügbar — der Server weist jeden dieser Aufrufe von sich aus zurück, nicht erst diese Seite.",
    "unreachable": "Diese Instanz ist gerade nicht erreichbar. Bitte später erneut versuchen.",
    "signIn": "Anmelden",
    "signingIn": "Anmelden…",
    "signInSub": "Melde dich als Server-Owner an, um diese Instanz zu verwalten.",
    "signInFailed": "Anmeldung fehlgeschlagen. Bitte E-Mail und Passwort prüfen.",
    "noTenants": "Noch keine Tenants. Lege den ersten an.",
    "loading": "Wird geladen…"
  }
}

export const SUPPORTED_LOCALES: SupportedLocale[] = ['en', 'de']

/** The browser's preferred locale if we speak it, else English. The admin surface has no per-account locale
 *  setting — it is a one-operator console, so the browser is the only signal worth reading. */
function browserLocale(): SupportedLocale {
  const tag = (navigator.languages?.[0] ?? navigator.language ?? 'en').slice(0, 2).toLowerCase()
  return (SUPPORTED_LOCALES as string[]).includes(tag) ? (tag as SupportedLocale) : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: browserLocale(),
  fallbackLocale: 'en',
  messages: { en, de },
})
