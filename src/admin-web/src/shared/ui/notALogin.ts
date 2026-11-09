// A field that holds no veyq account credential, and every field beside one: a share link's password (set, entered or
// kept), a PDF's, an invite's, an SMTP or object-store secret and the host, user and key fields around it. Password
// managers take any form with a password field for a login: they offer the person's logins there and ask to save
// whatever is typed, and they ignore `autocomplete="off"`. Each major one reads an opt-out attribute of its own instead
// (1Password, LastPass, Bitwarden, Dashlane), so such a field carries all of them: `v-bind="NOT_A_LOGIN"` on a `VeyqInput`,
// `not-a-login` on a `VeyqPassword`. An account credential's field (sign-in, registration, re-authentication, a new
// password, the Secret Key) never carries it: there the manager is wanted, steered by the standard `autocomplete` tokens.
export const NOT_A_LOGIN: Readonly<Record<string, string>> = Object.freeze({
  'data-1p-ignore': 'true',
  'data-lpignore': 'true',
  'data-bwignore': 'true',
  'data-form-type': 'other',
})
