//! The core, reached through libveyq.so's six functions (ADR 0127 decision 5; ADR 0123's Consequences, the bridge): the
//! wire's types ([wire], generated from the core's serial descriptors and bridge.h, never written here), a safe wrapper
//! over the functions, and the host function — the one way the core asks the device for something (its store).
//!
//! Memory: what this side hands in is read before the function returns; what the core hands to a callback is valid
//! only during that call, so it is copied at once. Threads: every answer, update and host request arrives on a core
//! thread; an answer or an update goes through an `async_channel` to whoever awaits it — on GTK's main loop, never a
//! widget touched off it.
use std::collections::BTreeMap;
use std::ffi::{CStr, CString, c_char, c_void};
use std::io;
use std::path::PathBuf;
use std::ptr;
use std::sync::Mutex;

/// The wire's types and the six `extern "C"` functions, as `:bridge:generateBindings` wrote them.
#[allow(dead_code, clippy::large_enum_variant, clippy::enum_variant_names)]
pub mod wire {
    include!(concat!(env!("OUT_DIR"), "/bridge.rs"));
}

use wire::{Answer, Call, Config, HostReply, HostRequest, Update, Watched};

/// What `veyq_init` answered.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Start {
    Started,
    AlreadyStarted,
    /// The config or the host function was refused — a defect of this side.
    Refused,
    /// This CPU has no AES instructions, which the suite's AES-256-GCM needs (ADR 0123's Consequences, the core on
    /// Linux): the app says so and stops.
    NoAes,
}

/// Starts the core once per process with [store] as the device's store, which the core reads from its own threads
/// until the process ends.
pub fn start(config: &Config, store: DeviceStore) -> Start {
    let json = CString::new(serde_json::to_string(config).expect("a Config writes")).expect("JSON holds no NUL");
    let ctx = Box::into_raw(Box::new(store)).cast::<c_void>();
    // SAFETY: a NUL-terminated config read before the call returns; [host] matches bridge.h's `veyq_host`; ctx is
    // a DeviceStore that lives as long as the core may ask (it is never freed once the core took it).
    let code = unsafe { wire::veyq_init(json.as_ptr(), host, ctx) };
    if code != 0 {
        // SAFETY: the core did not take ctx; it is ours again, from Box::into_raw above.
        drop(unsafe { Box::from_raw(ctx.cast::<DeviceStore>()) });
    }
    match code {
        0 => Start::Started,
        1 => Start::AlreadyStarted,
        3 => Start::NoAes,
        _ => Start::Refused,
    }
}

/// Ends the core; the application calls it when it quits.
pub fn shutdown() {
    // SAFETY: no arguments; safe at any time (before veyq_init it does nothing).
    unsafe { wire::veyq_shutdown() }
}

/// A call's answer or a watch's update that did not read as its type: the two sides were built from different models.
#[derive(Debug)]
pub struct Unreadable(pub serde_json::Error);

/// A call's answer and the payload beside it: the bytes of an `Answer::Bytes` (a member's picture, a blob's range),
/// copied out of the callback; None for every other answer.
pub type Answered = (Answer, Option<Vec<u8>>);

/// One action of the core, answered once. Before the core started it is never answered.
pub async fn call(call: &Call) -> Result<Answered, Unreadable> {
    let json = CString::new(serde_json::to_string(call).expect("a Call writes")).expect("JSON holds no NUL");
    let (tx, rx) = async_channel::bounded::<Result<Answered, Unreadable>>(1);
    let ctx = Box::into_raw(Box::new(tx)).cast::<c_void>();
    // SAFETY: the request is read before the call returns; ctx is taken back exactly once, by [answered].
    unsafe { wire::veyq_call(json.as_ptr(), ptr::null(), 0, ctx, answered) };
    rx.recv().await.unwrap_or_else(|_| unreachable!("the answer's sender is dropped only after it sent"))
}

unsafe extern "C" fn answered(ctx: *mut c_void, json: *const c_char, bytes: *const u8, len: usize) {
    // SAFETY: ctx is the sender [call] leaked for this one answer; the core answers once.
    let tx = unsafe { Box::from_raw(ctx.cast::<async_channel::Sender<Result<Answered, Unreadable>>>()) };
    // SAFETY: a NUL-terminated string valid during this call, copied here.
    let text = unsafe { CStr::from_ptr(json) }.to_string_lossy();
    // SAFETY: NULL, or `len` bytes valid during this call, copied here (the generated layer's one reading of them).
    let payload = unsafe { wire::payload(bytes, len) };
    let _ = tx.try_send(serde_json::from_str(&text).map(|answer| (answer, payload)).map_err(Unreadable));
}

/// [call] sent from GTK's main loop, its answer awaited there; an answer other than done is said on stderr by the call's
/// kind alone (never its fields: a sign-in's carry what the person typed).
pub fn send(call: Call) {
    gtk::glib::spawn_future_local(async move {
        match self::call(&call).await.map(|(answer, _)| answer) {
            Ok(Answer::Done | Answer::PotOpened { .. } | Answer::Groups { .. } | Answer::Hits { .. }) => {}
            other => {
                let kind = serde_json::to_value(&call).ok().and_then(|v| v["type"].as_str().map(str::to_owned)).unwrap_or_default();
                eprintln!("veyq: {kind} answered {other:?}");
            }
        }
    });
}

/// An observed model; dropping it ends the watch.
pub struct Watch(u64);

impl Drop for Watch {
    fn drop(&mut self) {
        // SAFETY: an id veyq_watch gave.
        unsafe { wire::veyq_unwatch(self.0) }
    }
}

/// Observes [what]: its state now, then each change, arrive through the receiver. None when the core refused it.
pub fn watch(what: &Watched) -> Option<(Watch, async_channel::Receiver<Result<Update, Unreadable>>)> {
    let json = CString::new(serde_json::to_string(what).expect("a Watched writes")).expect("JSON holds no NUL");
    let (tx, rx) = async_channel::unbounded::<Result<Update, Unreadable>>();
    // Kept until the process ends: bridge.h lets an update arrive after veyq_unwatch, until veyq_shutdown.
    let ctx = Box::into_raw(Box::new(tx)).cast::<c_void>();
    // SAFETY: the request is read before the call returns; ctx outlives every update (never freed).
    let id = unsafe { wire::veyq_watch(json.as_ptr(), ctx, updated) };
    if id == 0 {
        // SAFETY: refused, so the core holds no ctx; it is ours again.
        drop(unsafe { Box::from_raw(ctx.cast::<async_channel::Sender<Result<Update, Unreadable>>>()) });
        return None;
    }
    Some((Watch(id), rx))
}

unsafe extern "C" fn updated(ctx: *mut c_void, json: *const c_char, _bytes: *const u8, _len: usize) {
    // SAFETY: ctx is the sender [watch] leaked, never freed.
    let tx = unsafe { &*ctx.cast::<async_channel::Sender<Result<Update, Unreadable>>>() };
    // SAFETY: a NUL-terminated string valid during this call, copied here.
    let text = unsafe { CStr::from_ptr(json) }.to_string_lossy();
    let _ = tx.try_send(serde_json::from_str(&text).map_err(Unreadable));
}

/// The host function: the core's request, answered at once from this (core) thread — the store answers without
/// waiting for the core, as bridge.h asks.
unsafe extern "C" fn host(ctx: *mut c_void, token: u64, json: *const c_char, _bytes: *const u8, _len: usize) {
    // SAFETY: ctx is the DeviceStore [start] handed the core, alive for the process.
    let store = unsafe { &*ctx.cast::<DeviceStore>() };
    // SAFETY: a NUL-terminated string valid during this call, copied here.
    let text = unsafe { CStr::from_ptr(json) }.to_string_lossy();
    let reply = match serde_json::from_str::<HostRequest>(&text) {
        Ok(request) => store.answer(request),
        Err(_) => HostReply::Failed { error: "the host could not read the request".into() },
    };
    let reply = CString::new(serde_json::to_string(&reply).expect("a HostReply writes")).expect("JSON holds no NUL");
    // SAFETY: the reply is read before the call returns.
    unsafe { wire::veyq_reply(token, reply.as_ptr(), ptr::null(), 0) };
}

/// The device's store (`DeviceStore` of the core: its trust marks, the trusted accounts, this device's id): names and
/// values, kept in one JSON file only this user may read, written whole on each change.
pub struct DeviceStore {
    file: Option<PathBuf>,
    values: Mutex<BTreeMap<String, String>>,
}

impl DeviceStore {
    /// The store kept in [file]; a file that is not there yet is an empty store.
    pub fn open(file: PathBuf) -> io::Result<Self> {
        let values = match std::fs::read(&file) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(io::Error::other)?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => BTreeMap::new(),
            Err(e) => return Err(e),
        };
        Ok(Self { file: Some(file), values: Mutex::new(values) })
    }

    /// A store that keeps nothing past the process.
    pub fn in_memory() -> Self {
        Self { file: None, values: Mutex::new(BTreeMap::new()) }
    }

    /// The reply to one of the core's requests.
    pub fn answer(&self, request: HostRequest) -> HostReply {
        let mut values = self.values.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let changed = match request {
            HostRequest::StoreRead { name } => return HostReply::Value { value: values.get(&name).cloned() },
            HostRequest::StoreWrite { name, value } => values.insert(name, value.clone()).as_ref() != Some(&value),
            HostRequest::StoreRemove { name } => values.remove(&name).is_some(),
        };
        if changed && let Err(e) = self.save(&values) {
            return HostReply::Failed { error: format!("the device store did not write: {}", e.kind()) };
        }
        HostReply::Done
    }

    fn save(&self, values: &BTreeMap<String, String>) -> io::Result<()> {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let Some(file) = &self.file else { return Ok(()) };
        if let Some(dir) = file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let next = file.with_extension("next");
        let mut out = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&next)?;
        out.write_all(&serde_json::to_vec(values).map_err(io::Error::other)?)?;
        out.sync_all()?;
        std::fs::rename(next, file)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The Rust half of D7's round trip: every sample the JVM wrote (`WireTest`, one per variant of every type the
    /// roots reach) reads as its generated type and writes back to the same JSON, field for field. A sample of a type
    /// this side lacks is red ([wire::reserialize] is generated with the types), and so is an empty directory.
    #[test]
    fn every_sample_the_core_wrote_reads_back_field_for_field() {
        let dir = std::env::var("VEYQ_SAMPLES").expect("run.py sets VEYQ_SAMPLES");
        let mut read = 0;
        for entry in std::fs::read_dir(&dir).unwrap_or_else(|e| panic!("{dir}: {e}")) {
            let path = entry.unwrap().path();
            let name = path.file_stem().unwrap().to_str().unwrap().to_string();
            let (rust_type, _) = name.rsplit_once('-').unwrap_or_else(|| panic!("{name}: not <Type>-<n>"));
            let json = std::fs::read_to_string(&path).unwrap();
            let written: serde_json::Value = serde_json::from_str(&json).unwrap();
            let back = wire::reserialize(rust_type, &json)
                .unwrap_or_else(|| panic!("{name}: no wire type {rust_type} on this side"))
                .unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(back, written, "{name}");
            read += 1;
        }
        assert!(read > 0, "no sample in {dir}");
        eprintln!("{read} samples read back");
    }

    /// ADR 0128 decision 1: the payload beside an answer is copied before the callback returns — the core's buffer may
    /// be gone right after — and NULL is no payload, while an empty one is still one (an empty range).
    #[test]
    fn an_answers_payload_is_copied_before_the_callback_returns() {
        fn answer(json: &str, bytes: *const u8, len: usize) -> Answered {
            let (tx, rx) = async_channel::bounded::<Result<Answered, Unreadable>>(1);
            let ctx = Box::into_raw(Box::new(tx)).cast::<c_void>();
            let json = CString::new(json).unwrap();
            // SAFETY: ctx is a leaked sender, taken back by answered; json and bytes are valid during the call.
            unsafe { answered(ctx, json.as_ptr(), bytes, len) };
            rx.try_recv().expect("answered at once").expect("the answer reads")
        }
        let picture = r#"{"type":"Bytes","mediaType":"image/png"}"#;
        let mut core_buffer = vec![0x89, b'P', b'N', b'G', 0, 255];
        let (a, payload) = answer(picture, core_buffer.as_ptr(), core_buffer.len());
        core_buffer.fill(0); // the core reuses its buffer once the callback returned
        drop(core_buffer);
        assert_eq!(a, Answer::Bytes { media_type: Some("image/png".into()) });
        assert_eq!(payload, Some(vec![0x89, b'P', b'N', b'G', 0, 255]));

        let empty = [0u8; 1];
        assert_eq!(answer(r#"{"type":"Bytes","mediaType":null}"#, empty.as_ptr(), 0).1, Some(vec![]));
        assert_eq!(answer(r#"{"type":"Done"}"#, ptr::null(), 0), (Answer::Done, None));
    }

    #[test]
    fn the_store_keeps_what_the_core_wrote_across_a_restart() {
        let dir = std::env::temp_dir().join(format!("veyq-store-{}", std::process::id()));
        let file = dir.join("device-store.json");
        let store = DeviceStore::open(file.clone()).unwrap();
        let read = |s: &DeviceStore, name: &str| s.answer(HostRequest::StoreRead { name: name.into() });
        assert_eq!(read(&store, "a"), HostReply::Value { value: None });
        assert_eq!(store.answer(HostRequest::StoreWrite { name: "a".into(), value: "1".into() }), HostReply::Done);
        assert_eq!(store.answer(HostRequest::StoreWrite { name: "b".into(), value: "2".into() }), HostReply::Done);
        assert_eq!(store.answer(HostRequest::StoreRemove { name: "b".into() }), HostReply::Done);
        let again = DeviceStore::open(file.clone()).unwrap();
        assert_eq!(read(&again, "a"), HostReply::Value { value: Some("1".into()) });
        assert_eq!(read(&again, "b"), HostReply::Value { value: None });
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(std::fs::metadata(&file).unwrap().permissions().mode() & 0o777, 0o600);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
