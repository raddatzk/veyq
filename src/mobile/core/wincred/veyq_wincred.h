/*
 * Windows' key stores for the core's WindowsDeviceKeys (mingwMain, session/WindowsDeviceKeys.kt): DPAPI for the bind
 * keys, the secrets and the unlock key where Windows Hello is not set up; the Microsoft Passport Key Storage Provider
 * (NCrypt) for the unlock key behind Windows Hello (ADR 0127's Consequences).
 *
 * Every Windows call is made here, in C, and only plain integers, byte buffers and UTF-16 strings cross into Kotlin:
 * the interop exposes this header alone (wincred.def's headerFilter), so none of windows.h's types meets
 * platform.windows' copies of them.
 *
 * Every function returns 0 or a Windows status: an NCrypt SECURITY_STATUS / HRESULT as it is, a Win32 error from
 * GetLastError() as HRESULT_FROM_WIN32.
 */
#ifndef VEYQ_WINCRED_H
#define VEYQ_WINCRED_H

#include <stdint.h>
#include <wchar.h>
#include <windows.h>
#include <wincrypt.h>
#include <dpapi.h>
#include <ncrypt.h>
#include <sddl.h>

/* ncrypt.h has no MS_NGC_KEY_STORAGE_PROVIDER: the provider's name as Windows lists it (`certutil -csplist`). */
#define VEYQ_NGC_PROVIDER L"Microsoft Passport Key Storage Provider"

static inline int32_t veyq_last_error(void) { return (int32_t)HRESULT_FROM_WIN32(GetLastError()); }

/*
 * DPAPI for the current user, never with UI (CRYPTPROTECT_UI_FORBIDDEN): [protect] 1 protects [in], 0 unprotects it,
 * both under [entropy]. On success *out holds LocalAlloc'ed bytes the caller hands to veyq_dpapi_free.
 */
static inline int32_t veyq_dpapi(int32_t protect, const uint8_t *in, uint32_t inLen, const uint8_t *entropy,
                                 uint32_t entropyLen, uint8_t **out, uint32_t *outLen) {
    BYTE none = 0;
    DATA_BLOB i = { inLen, inLen ? (BYTE *)in : &none };
    DATA_BLOB e = { entropyLen, entropyLen ? (BYTE *)entropy : &none };
    DATA_BLOB o = { 0, NULL };
    BOOL ok = protect ? CryptProtectData(&i, NULL, &e, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &o)
                      : CryptUnprotectData(&i, NULL, &e, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &o);
    if (!ok) return veyq_last_error();
    *out = o.pbData;
    *outLen = o.cbData;
    return 0;
}

static inline void veyq_dpapi_free(uint8_t *p, uint32_t len) {
    if (!p) return;
    SecureZeroMemory(p, len);
    LocalFree(p);
}

/* The current user's SID as a string (S-1-5-21-…) into [buf] of [cap] UTF-16 units, NUL-terminated; *len its length. */
static inline int32_t veyq_user_sid(wchar_t *buf, int32_t cap, int32_t *len) {
    HANDLE token = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return veyq_last_error();
    DWORD n = 0;
    GetTokenInformation(token, TokenUser, NULL, 0, &n);
    TOKEN_USER *user = n ? (TOKEN_USER *)LocalAlloc(LPTR, n) : NULL;
    int32_t status = 0;
    LPWSTR text = NULL;
    if (!user) status = veyq_last_error();
    else if (!GetTokenInformation(token, TokenUser, user, n, &n)) status = veyq_last_error();
    else if (!ConvertSidToStringSidW(user->User.Sid, &text)) status = veyq_last_error();
    else {
        size_t length = wcslen(text);
        *len = (int32_t)length;
        if ((int32_t)length < cap) wcscpy(buf, text);
        else status = (int32_t)HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
        LocalFree(text);
    }
    if (user) LocalFree(user);
    CloseHandle(token);
    return status;
}

/*
 * Whether Windows Hello is set up for [sid]: the length of the name of its default decryption key in the Passport
 * provider, 0 where there is none (no PIN, no Hello). `NgcGetDefaultDecryptionKeyName` is cryptngc.dll's, not in any
 * SDK header or import library, so it is loaded at run time (KeePassWinHello reads Hello's availability the same way).
 * Its name is freed with CoTaskMemFree, as a .NET marshaller frees an `out string`.
 */
static inline int32_t veyq_ngc_default_key_length(const wchar_t *sid, int32_t *len) {
    typedef HRESULT(WINAPI * Fn)(LPCWSTR, DWORD, DWORD, LPWSTR *);
    *len = 0;
    HMODULE module = LoadLibraryExW(L"cryptngc.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!module) return veyq_last_error();
    Fn fn = (Fn)(void *)GetProcAddress(module, "NgcGetDefaultDecryptionKeyName");
    if (!fn) {
        int32_t status = veyq_last_error();
        FreeLibrary(module);
        return status;
    }
    LPWSTR name = NULL;
    HRESULT hr = fn(sid, 0, 0, &name);
    if (SUCCEEDED(hr) && name) *len = (int32_t)wcslen(name);
    if (name) CoTaskMemFree(name);
    FreeLibrary(module);
    return (int32_t)hr;
}

static inline int32_t veyq_ngc_open_provider(uint64_t *provider) {
    NCRYPT_PROV_HANDLE h = 0;
    SECURITY_STATUS s = NCryptOpenStorageProvider(&h, VEYQ_NGC_PROVIDER, 0);
    *provider = (uint64_t)h;
    return (int32_t)s;
}

static inline void veyq_ngc_free(uint64_t handle) {
    if (handle) NCryptFreeObject((NCRYPT_HANDLE)handle);
}

/* Opens the persisted key [name]; [silent] 1 refuses any UI (an encryption needs none). */
static inline int32_t veyq_ngc_open_key(uint64_t provider, const wchar_t *name, int32_t silent, uint64_t *key) {
    NCRYPT_KEY_HANDLE h = 0;
    SECURITY_STATUS s = NCryptOpenKey((NCRYPT_PROV_HANDLE)provider, &h, name, 0, silent ? NCRYPT_SILENT_FLAG : 0);
    *key = (uint64_t)h;
    return (int32_t)s;
}

/*
 * The prompt's owner window ([window], an HWND; 0 leaves it unset) and the words it shows ([context]; empty leaves
 * them unset). A window the provider refuses as such (NTE_BAD_DATA) is not fatal: the prompt opens without an owner.
 */
static inline int32_t veyq_ngc_ui(uint64_t key, uint64_t window, const wchar_t *context) {
    if (window) {
        HWND hwnd = (HWND)(uintptr_t)window;
        SECURITY_STATUS s = NCryptSetProperty((NCRYPT_HANDLE)key, NCRYPT_WINDOW_HANDLE_PROPERTY, (PBYTE)&hwnd, sizeof hwnd, 0);
        if (s < 0 && s != (SECURITY_STATUS)NTE_BAD_DATA) return (int32_t)s;
    }
    if (context && *context) {
        SECURITY_STATUS s = NCryptSetProperty((NCRYPT_HANDLE)key, NCRYPT_USE_CONTEXT_PROPERTY, (PBYTE)context,
                                              (DWORD)((wcslen(context) + 1) * sizeof(wchar_t)), 0);
        if (s < 0) return (int32_t)s;
    }
    return 0;
}

/*
 * Creates the RSA-2048 key [name] in the Passport provider, replacing one there: for decryption (and signing, as
 * Hello's own keys are), its use always asking the person (NgcCacheType AUTH_MANDATORY, under its older name where the
 * newer is refused). Finalizing it may itself ask for Hello.
 */
static inline int32_t veyq_ngc_create_key(uint64_t provider, const wchar_t *name, uint64_t window, const wchar_t *context,
                                          uint64_t *key) {
    NCRYPT_KEY_HANDLE h = 0;
    *key = 0;
    SECURITY_STATUS s = NCryptCreatePersistedKey((NCRYPT_PROV_HANDLE)provider, &h, NCRYPT_RSA_ALGORITHM, name, 0,
                                                 NCRYPT_OVERWRITE_KEY_FLAG);
    if (s < 0) return (int32_t)s;
    DWORD length = 2048;
    DWORD usage = NCRYPT_ALLOW_DECRYPT_FLAG | NCRYPT_ALLOW_SIGNING_FLAG;
    DWORD cache = 1; /* NCRYPT_NGC_CACHE_TYPE_PROPERTY_AUTH_MANDATORY_FLAG */
    s = NCryptSetProperty(h, NCRYPT_LENGTH_PROPERTY, (PBYTE)&length, sizeof length, 0);
    if (s >= 0) s = NCryptSetProperty(h, NCRYPT_KEY_USAGE_PROPERTY, (PBYTE)&usage, sizeof usage, 0);
    if (s >= 0) {
        s = NCryptSetProperty(h, L"NgcCacheType", (PBYTE)&cache, sizeof cache, 0);
        if (s < 0) s = NCryptSetProperty(h, L"NgcCacheTypeProperty", (PBYTE)&cache, sizeof cache, 0);
    }
    if (s >= 0) s = veyq_ngc_ui((uint64_t)h, window, context);
    if (s >= 0) s = NCryptFinalizeKey(h, 0);
    if (s < 0) {
        NCryptFreeObject(h);
        return (int32_t)s;
    }
    *key = (uint64_t)h;
    return 0;
}

/* Asks for the gesture at this use even where Hello cached one (KeePassWinHello sets it before each decryption). */
static inline int32_t veyq_ngc_require_gesture(uint64_t key) {
    DWORD one = 1;
    return (int32_t)NCryptSetProperty((NCRYPT_HANDLE)key, L"PinCacheIsGestureRequired", (PBYTE)&one, sizeof one, 0);
}

/*
 * RSA with PKCS#1 v1.5 padding, as the Passport provider offers it: [decrypt] 0 encrypts under the public half (no
 * prompt), 1 decrypts (Windows Hello asks the person). [out] holds [cap] bytes; *outLen is what was written.
 */
static inline int32_t veyq_ngc_crypt(uint64_t key, int32_t decrypt, const uint8_t *in, uint32_t inLen, uint8_t *out,
                                     uint32_t cap, uint32_t *outLen) {
    DWORD n = 0;
    SECURITY_STATUS s = decrypt
        ? NCryptDecrypt((NCRYPT_KEY_HANDLE)key, (PBYTE)in, inLen, NULL, out, cap, &n, NCRYPT_PAD_PKCS1_FLAG)
        : NCryptEncrypt((NCRYPT_KEY_HANDLE)key, (PBYTE)in, inLen, NULL, out, cap, &n, NCRYPT_PAD_PKCS1_FLAG);
    *outLen = (uint32_t)n;
    return (int32_t)s;
}

/* Deletes the persisted key and frees its handle (whatever the outcome). */
static inline int32_t veyq_ngc_delete_key(uint64_t key) {
    return (int32_t)NCryptDeleteKey((NCRYPT_KEY_HANDLE)key, 0);
}

/* The console's window, 0 where the process has none: the prompt's owner for the Hello probe, a console process. */
static inline uint64_t veyq_console_window(void) { return (uint64_t)(uintptr_t)GetConsoleWindow(); }

#endif
