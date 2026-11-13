import { describe, expect, it } from 'vitest'
import {
  asCallbackResult, asToken, authorizeUrl, callbackUrl, challengeOf, parseCallback, randomState, randomVerifier, refreshRequest,
  tokenRequest, SPOTIFY_SCOPES,
} from './spotifyAuth'

// Spotify's Authorization Code flow with PKCE as the music frame runs it (ADR 0131 decision 3).
describe('spotifyAuth', () => {
  it('the S256 challenge is RFC 7636 appendix B\'s', async () => {
    expect(await challengeOf('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  })

  it('a verifier is 64 unreserved characters and a state 32 base64url ones', () => {
    const v = randomVerifier()
    expect(v).toMatch(/^[A-Za-z0-9._~-]{64}$/)
    expect(randomVerifier()).not.toBe(v)
    expect(randomState()).toMatch(/^[A-Za-z0-9_-]{32}$/)
  })

  it('the authorize address names the public client, the music host\'s callback, the challenge and the state', () => {
    const redirectUri = callbackUrl('https://music.example.org')
    expect(redirectUri).toBe('https://music.example.org/music-frame/spotify-callback')
    const url = new URL(authorizeUrl({ clientId: 'cid', redirectUri, challenge: 'ch', state: 'st' }))
    expect(url.origin + url.pathname).toBe('https://accounts.spotify.com/authorize')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: 'cid', response_type: 'code', redirect_uri: redirectUri, code_challenge_method: 'S256', code_challenge: 'ch', state: 'st',
      scope: SPOTIFY_SCOPES,
    })
  })

  it('the callback reads a code with its state, or Spotify\'s error; the relay is checked the same way', () => {
    expect(parseCallback('?code=c1&state=s1')).toEqual({ code: 'c1', state: 's1' })
    expect(parseCallback('?error=access_denied&state=s1')).toEqual({ error: 'access_denied', state: 's1' })
    expect(parseCallback('?code=c1')).toBeNull()
    expect(parseCallback('')).toBeNull()
    expect(asCallbackResult({ kind: 'veyq-spotify-callback', code: 'c1', state: 's1' })).toEqual({ code: 'c1', state: 's1' })
    expect(asCallbackResult({ kind: 'other', code: 'c1', state: 's1' })).toBeNull()
  })

  it('the exchange sends the verifier and no secret; a refresh its token and the client', () => {
    expect(Object.fromEntries(tokenRequest({ clientId: 'cid', code: 'c1', redirectUri: 'r', verifier: 'v' }))).toEqual({
      grant_type: 'authorization_code', code: 'c1', redirect_uri: 'r', client_id: 'cid', code_verifier: 'v',
    })
    expect(Object.fromEntries(refreshRequest({ clientId: 'cid', refreshToken: 'rt' }))).toEqual({ grant_type: 'refresh_token', refresh_token: 'rt', client_id: 'cid' })
  })

  it('a token is refreshed a minute before it expires, and keeps the refresh token Spotify did not rotate', () => {
    expect(asToken({ access_token: 'a', refresh_token: 'r', expires_in: 3600 }, 1000)).toEqual({ accessToken: 'a', refreshToken: 'r', refreshAt: 1000 + 3540_000 })
    expect(asToken({ access_token: 'a2', expires_in: 3600 }, 0, 'r')?.refreshToken).toBe('r')
    expect(asToken({ error: 'invalid_grant' }, 0)).toBeNull()
  })
})
