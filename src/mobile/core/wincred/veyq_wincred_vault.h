/*
 * veyq_wincred.h's Credential Locker internals: WinRT's PasswordVault and PasswordCredential through their C ABI. Kept
 * apart so wincred.def's headerFilter (veyq_wincred.h alone) exposes none of it — no HSTRING, IID or vtable meets Kotlin.
 */
#ifndef VEYQ_WINCRED_VAULT_H
#define VEYQ_WINCRED_VAULT_H

#include <windows.h>
#include <roapi.h>
#include <winstring.h>

#define VEYQ_VAULT_USER L"veyq"

typedef struct veyq_inspectable_vtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void *self, REFIID iid, void **out);
    ULONG (STDMETHODCALLTYPE *AddRef)(void *self);
    ULONG (STDMETHODCALLTYPE *Release)(void *self);
    void *GetIids, *GetRuntimeClassName, *GetTrustLevel;
} veyq_inspectable_vtbl;

typedef struct veyq_vault_vtbl {
    veyq_inspectable_vtbl base;
    HRESULT (STDMETHODCALLTYPE *Add)(void *self, void *credential);
    HRESULT (STDMETHODCALLTYPE *Remove)(void *self, void *credential);
    HRESULT (STDMETHODCALLTYPE *Retrieve)(void *self, HSTRING resource, HSTRING userName, void **credential);
    void *FindAllByResource, *FindAllByUserName, *RetrieveAll;
} veyq_vault_vtbl;

typedef struct veyq_credential_vtbl {
    veyq_inspectable_vtbl base;
    HRESULT (STDMETHODCALLTYPE *get_Resource)(void *self, HSTRING *value);
    HRESULT (STDMETHODCALLTYPE *put_Resource)(void *self, HSTRING value);
    HRESULT (STDMETHODCALLTYPE *get_UserName)(void *self, HSTRING *value);
    HRESULT (STDMETHODCALLTYPE *put_UserName)(void *self, HSTRING value);
    HRESULT (STDMETHODCALLTYPE *get_Password)(void *self, HSTRING *value);
    HRESULT (STDMETHODCALLTYPE *put_Password)(void *self, HSTRING value);
    HRESULT (STDMETHODCALLTYPE *RetrievePassword)(void *self);
    void *get_Properties;
} veyq_credential_vtbl;

typedef struct veyq_vault { const veyq_vault_vtbl *v; } veyq_vault;
typedef struct veyq_credential { const veyq_credential_vtbl *v; } veyq_credential;

static const IID VEYQ_IID_PASSWORD_VAULT = {0x61fd2c0b, 0xc8d4, 0x48c1, {0xa5, 0x4f, 0xbc, 0x5a, 0x64, 0x20, 0x5a, 0xf2}};
static const IID VEYQ_IID_PASSWORD_CREDENTIAL = {0x6ab18989, 0xc720, 0x41a7, {0xa6, 0xc1, 0xfe, 0xad, 0xb3, 0x63, 0x29, 0xa0}};

/* [s] as an HSTRING ([*out] null for an empty one, which WinRT takes as ""). */
static inline HRESULT veyq_hstring(const wchar_t *s, HSTRING *out) {
    return WindowsCreateString(s, (UINT32)wcslen(s), out);
}

/* Runtime class [cls] activated, queried for [iid]. */
static inline HRESULT veyq_activate(const wchar_t *cls, REFIID iid, void **out) {
    HSTRING name = NULL;
    HRESULT hr = veyq_hstring(cls, &name);
    if (FAILED(hr)) return hr;
    void *inspectable = NULL;
    hr = RoActivateInstance(name, (IInspectable **)&inspectable);
    WindowsDeleteString(name);
    if (FAILED(hr)) return hr;
    hr = ((veyq_inspectable_vtbl *)*(void **)inspectable)->QueryInterface(inspectable, iid, out);
    ((veyq_inspectable_vtbl *)*(void **)inspectable)->Release(inspectable);
    return hr;
}

/* The calling thread's apartment: 1 where this call initialised it (and so uninitialises it), 0 where it stood. */
static inline int veyq_ro_enter(HRESULT *hr) {
    HRESULT r = RoInitialize(RO_INIT_MULTITHREADED);
    if (r == S_OK || r == S_FALSE) { *hr = S_OK; return 1; }
    *hr = r == RPC_E_CHANGED_MODE ? S_OK : r;
    return 0;
}

/* The vault's credential of [resource] (with VEYQ_VAULT_USER), its password retrieved; an error where there is none. */
static inline HRESULT veyq_vault_retrieve(veyq_vault *vault, const wchar_t *resource, veyq_credential **out) {
    HSTRING r = NULL, u = NULL;
    HRESULT hr = veyq_hstring(resource, &r);
    if (SUCCEEDED(hr)) hr = veyq_hstring(VEYQ_VAULT_USER, &u);
    if (SUCCEEDED(hr)) hr = vault->v->Retrieve(vault, r, u, (void **)out);
    WindowsDeleteString(r);
    WindowsDeleteString(u);
    return hr;
}

#endif
