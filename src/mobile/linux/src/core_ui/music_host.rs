//! The host's half of a node's music (ADR 0142 decisions 2 and 3): the core's player decides which track plays, when and
//! how loud; this side plays bytes and lends the desktop's media controls.
//!
//! - **The two decks** (`Audio.Load|Play|Pause|Seek|Position|Ramp|Clear`): each a `gtk::MediaFile` over
//!   [crate::core_ui::reader_stream] — the core's reader, decrypted in memory, never a file (ADR 0047) — that the system's GStreamer
//!   decodes, as a Depot file's medium plays ([crate::core_ui::player]). A load is answered with the file's length once GTK says it
//!   is prepared, `Failed` where its error comes first (the core then skips the track, ADR 0142 decision 9); its end and a
//!   later error go back as `Audio.Ended`/`Audio.Failed` with the load's number. A ramp is stepped here, every
//!   [RAMP_STEP_MS] at most (a crossfade is two ramps the core asks at once); a deck keeps its last volume across a clear,
//!   which the core's next load on it starts from.
//! - **MPRIS** (`Controls.Bind|Show|Clear`): `org.mpris.MediaPlayer2` and its `.Player` on the session bus through gio's
//!   D-Bus, under [MPRIS_NAME] while a queue is loaded (a Flatpak may own that name without a finish-arg); the desktop's play,
//!   pause, next, previous and seek come back to the core as `Player.*` calls — its play is `Player.Resume`.
//! - **`Words.Say`**: a notice in this app's words, for a core function that takes a string (an untitled track's title).
//!
//! The core asks from its own threads and the host function must not wait for the core ([crate::bridge::host]), so every
//! deck and control request is handed to GTK's main loop in the order it came and answered from there.
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::time::Duration;

use gtk::glib::variant::ToVariant;
use gtk::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Call, wire::HostReply, wire::HostRequest, wire::NowPlayingShown};
use crate::core_ui::reader_stream::{CoreReader, ReaderStream};

/// The longest step of a volume ramp (ADR 0142 decision 2: at most 50 ms).
pub const RAMP_STEP_MS: i64 = 40;
const _: () = assert!(RAMP_STEP_MS <= 50, "a ramp's step is at most 50 ms");

/// How long a load may take to be prepared before it is answered as failed (the core's own wait is 30 s).
const LOAD_WAIT: Duration = Duration::from_secs(20);

/// The bus name the desktop's media controls find the app under: MPRIS's prefix and the app's id.
pub const MPRIS_NAME: &str = concat!("org.mpris.MediaPlayer2.", env!("VEYQ_APP_ID"));
const MPRIS_PATH: &str = "/org/mpris/MediaPlayer2";
const MPRIS_ROOT: &str = "org.mpris.MediaPlayer2";
const MPRIS_PLAYER: &str = "org.mpris.MediaPlayer2.Player";

/// The two interfaces MPRIS asks for, as gio registers them.
const MPRIS_XML: &str = r#"<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
    <method name="SetPosition"><arg direction="in" name="TrackId" type="o"/><arg direction="in" name="Position" type="x"/></method>
    <method name="OpenUri"><arg direction="in" name="Uri" type="s"/></method>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="Rate" type="d" access="read"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="read"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>"#;

/// One deck's platform player and what it plays: load [load] of the core's, its stream, and the load's ask while it waits.
struct Deck {
    load: i64,
    media: gtk::MediaFile,
    stream: ReaderStream,
    asked: Cell<Option<u64>>,
    waiting: RefCell<Option<glib::SourceId>>,
}

#[derive(Default)]
struct Decks {
    decks: HashMap<i64, Deck>,
    /// Each deck's volume as last set, kept across its clears: the next load starts at it.
    volumes: HashMap<i64, f64>,
    ramps: HashMap<i64, glib::SourceId>,
}

/// The MPRIS object while a queue is loaded: the bus name's owner, the connection and the registrations on it.
#[derive(Default)]
struct Controls {
    owner: Option<gio::OwnerId>,
    connection: Option<gio::DBusConnection>,
    registered: Vec<gio::RegistrationId>,
    now: Option<NowPlayingShown>,
}

thread_local! {
    static DECKS: RefCell<Decks> = RefCell::new(Decks::default());
    static CONTROLS: RefCell<Controls> = RefCell::new(Controls::default());
}

/// Takes the core's music requests (called from a core thread, inside the host function): each deck and control request
/// goes to GTK's main loop in order and is answered there (`Some(None)`: nothing to reply now); `Words.Say` is answered
/// here (`Some(Some(reply))`); any other is not this module's (None).
pub fn take(token: u64, request: &HostRequest) -> Option<Option<HostReply>> {
    match request {
        HostRequest::WordsSay { words } => Some(Some(HostReply::Value { value: Some(crate::core_ui::said::notice(words)) })),
        HostRequest::AudioLoad { .. }
        | HostRequest::AudioPlay { .. }
        | HostRequest::AudioPause { .. }
        | HostRequest::AudioSeek { .. }
        | HostRequest::AudioPosition { .. }
        | HostRequest::AudioRamp { .. }
        | HostRequest::AudioClear { .. }
        | HostRequest::ControlsBind
        | HostRequest::ControlsShow { .. }
        | HostRequest::ControlsClear => {
            let request = request.clone();
            glib::MainContext::default().invoke(move || {
                if let Some(reply) = on_main(token, request) {
                    bridge::reply(token, &reply);
                }
            });
            Some(None)
        }
        _ => None,
    }
}

/// One request on GTK's main loop: its reply now, or None where it comes later (a load, once prepared).
fn on_main(token: u64, request: HostRequest) -> Option<HostReply> {
    let done = Some(HostReply::Done);
    match request {
        HostRequest::AudioLoad { deck, load, reader, size, media_type } => {
            self::load(token, deck, load, reader, size, &media_type);
            None
        }
        HostRequest::AudioPlay { deck } => Some(with_deck(deck, |d| {
            if d.media.error().is_some() {
                return HostReply::Failed { error: "the deck's medium failed".into() };
            }
            d.media.play();
            HostReply::Done
        })),
        HostRequest::AudioPause { deck } => {
            with_deck(deck, |d| {
                d.media.pause();
                HostReply::Done
            });
            done
        }
        HostRequest::AudioSeek { deck, position_ms } => {
            with_deck(deck, |d| {
                d.media.seek(position_ms.max(0) * 1000);
                HostReply::Done
            });
            done
        }
        HostRequest::AudioPosition { deck } => {
            Some(with_deck(deck, |d| HostReply::Value { value: Some((d.media.timestamp() / 1000).to_string()) }))
        }
        HostRequest::AudioRamp { deck, to, over_ms } => {
            ramp(deck, to.clamp(0.0, 1.0), over_ms);
            done
        }
        HostRequest::AudioClear { deck } => {
            clear(deck);
            done
        }
        HostRequest::ControlsBind => {
            bind();
            done
        }
        HostRequest::ControlsShow { now } => {
            show(now);
            done
        }
        HostRequest::ControlsClear => {
            unbind();
            done
        }
        _ => Some(HostReply::Failed { error: "not a music request".into() }),
    }
}

/// [f] over deck [deck]'s player; `Failed` where the deck plays nothing.
fn with_deck(deck: i64, f: impl FnOnce(&Deck) -> HostReply) -> HostReply {
    DECKS.with_borrow(|d| match d.decks.get(&deck) {
        Some(d) => f(d),
        None => HostReply::Failed { error: format!("deck {deck} holds nothing") },
    })
}

/// Load [load] of the track behind core reader [reader] onto deck [deck], answered once prepared.
fn load(token: u64, deck: i64, load: i64, reader: i64, size: i64, media_type: &str) {
    clear(deck);
    eprintln!("veyq: deck {deck}: load {load}, {size} B of {media_type}");
    let stream = ReaderStream::new(size.max(0) as u64, CoreReader(reader));
    let media = gtk::MediaFile::for_input_stream(&stream);
    media.set_volume(DECKS.with_borrow(|d| d.volumes.get(&deck).copied().unwrap_or(1.0)));
    media.connect_prepared_notify(move |media| {
        if media.is_prepared() {
            let ms = media.duration() / 1000;
            eprintln!("veyq: deck {deck}: load {load} prepared, {ms} ms");
            answer(deck, load, HostReply::Value { value: (ms > 0).then(|| ms.to_string()) });
        }
    });
    media.connect_error_notify(move |media| {
        let Some(error) = media.error() else { return };
        eprintln!("veyq: deck {deck}: load {load} does not play here: {error}");
        if !answer(deck, load, HostReply::Failed { error: "the platform cannot play it".into() }) && current(deck, load) {
            bridge::send(Call::AudioFailed { deck, load });
        }
    });
    media.connect_ended_notify(move |media| {
        if media.is_ended() && current(deck, load) {
            eprintln!("veyq: deck {deck}: load {load} ended");
            bridge::send(Call::AudioEnded { deck, load });
        }
    });
    let waiting = glib::timeout_add_local_once(LOAD_WAIT, move || {
        // this source is spent: taken out first, so the answer does not remove it again
        let pending = DECKS.with_borrow(|d| {
            d.decks.get(&deck).filter(|d| d.load == load).is_some_and(|d| {
                d.waiting.take();
                d.asked.get().is_some()
            })
        });
        if pending {
            eprintln!("veyq: deck {deck}: load {load} was not prepared in {} s", LOAD_WAIT.as_secs());
            answer(deck, load, HostReply::Failed { error: "the platform did not open it in time".into() });
        }
    });
    let d = Deck { load, media, stream, asked: Cell::new(Some(token)), waiting: RefCell::new(Some(waiting)) };
    DECKS.with_borrow_mut(|decks| decks.decks.insert(deck, d));
}

/// Whether deck [deck] plays load [load] still.
fn current(deck: i64, load: i64) -> bool {
    DECKS.with_borrow(|d| d.decks.get(&deck).is_some_and(|d| d.load == load))
}

/// Answers the load's ask with [reply] where it still waits; whether it did.
fn answer(deck: i64, load: i64, reply: HostReply) -> bool {
    let asked = DECKS.with_borrow(|d| {
        d.decks.get(&deck).filter(|d| d.load == load).and_then(|d| {
            if let Some(w) = d.waiting.take() {
                w.remove();
            }
            d.asked.take()
        })
    });
    match asked {
        Some(token) => {
            bridge::reply(token, &reply);
            true
        }
        None => false,
    }
}

/// Deck [deck] let go: its ramp ended, its player stopped and cleared, its stream closed (the core's reader with it); a
/// load still asked for is answered failed.
fn clear(deck: i64) {
    let (gone, ramp) = DECKS.with_borrow_mut(|d| (d.decks.remove(&deck), d.ramps.remove(&deck)));
    if let Some(r) = ramp {
        r.remove();
    }
    let Some(d) = gone else { return };
    if let Some(w) = d.waiting.take() {
        w.remove();
    }
    if let Some(token) = d.asked.take() {
        bridge::reply(token, &HostReply::Failed { error: "the deck was cleared".into() });
    }
    d.media.pause();
    d.media.clear();
    if let Err(e) = d.stream.close(gio::Cancellable::NONE) {
        eprintln!("veyq: deck {deck}: its stream did not close: {e}");
    }
}

/// The volumes a ramp from [from] to [to] over [over_ms] steps through, each [RAMP_STEP_MS] after the last, [to] last;
/// none longer than [RAMP_STEP_MS] apart. A ramp of no length is [to] at once.
pub fn ramp_steps(from: f64, to: f64, over_ms: i64) -> Vec<f64> {
    if over_ms <= 0 {
        return vec![to];
    }
    let n = (over_ms + RAMP_STEP_MS - 1) / RAMP_STEP_MS;
    (1..=n).map(|i| if i == n { to } else { from + (to - from) * i as f64 / n as f64 }).collect()
}

/// Deck [deck]'s volume moved to [to] over [over_ms] in steps; a ramp under way is replaced.
fn ramp(deck: i64, to: f64, over_ms: i64) {
    let from = DECKS.with_borrow_mut(|d| {
        if let Some(r) = d.ramps.remove(&deck) {
            r.remove();
        }
        d.decks.get(&deck).map(|d| d.media.volume()).or_else(|| d.volumes.get(&deck).copied()).unwrap_or(1.0)
    });
    let steps = ramp_steps(from, to, over_ms);
    set_volume(deck, steps[0]);
    if steps.len() == 1 {
        return;
    }
    let next = Cell::new(1usize);
    let id = glib::timeout_add_local(Duration::from_millis(RAMP_STEP_MS as u64), move || {
        let i = next.get();
        set_volume(deck, steps[i]);
        next.set(i + 1);
        if i + 1 < steps.len() {
            glib::ControlFlow::Continue
        } else {
            DECKS.with_borrow_mut(|d| d.ramps.remove(&deck));
            glib::ControlFlow::Break
        }
    });
    DECKS.with_borrow_mut(|d| d.ramps.insert(deck, id));
}

fn set_volume(deck: i64, v: f64) {
    DECKS.with_borrow_mut(|d| {
        d.volumes.insert(deck, v);
        if let Some(d) = d.decks.get(&deck) {
            d.media.set_volume(v);
        }
    });
}

/// The MPRIS track id of [track] (an object path: what the core's id holds beyond `[A-Za-z0-9_]` as `_`).
pub fn track_path(track: &str) -> String {
    let id: String = track.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    format!("/app/veyq/track/{}", if id.is_empty() { "none".into() } else { id })
}

/// MPRIS's playback status of [now].
pub fn status_of(now: Option<&NowPlayingShown>) -> &'static str {
    match now {
        None => "Stopped",
        Some(n) if n.playing => "Playing",
        Some(_) => "Paused",
    }
}

/// MPRIS's metadata of [now]: its track id, title, album (the node's name) and length (µs, where known).
fn metadata(now: Option<&NowPlayingShown>) -> glib::Variant {
    let mut m: HashMap<String, glib::Variant> = HashMap::new();
    if let Some(n) = now {
        let path = glib::variant::ObjectPath::try_from(track_path(&n.track_id)).expect("a valid object path");
        m.insert("mpris:trackid".into(), path.to_variant());
        m.insert("xesam:title".into(), n.title.to_variant());
        if let Some(album) = &n.album {
            m.insert("xesam:album".into(), album.to_variant());
        }
        if n.duration_ms > 0 {
            m.insert("mpris:length".into(), (n.duration_ms * 1000).to_variant());
        }
    }
    m.to_variant()
}

/// A property of the MPRIS object as it stands.
fn property(interface: &str, name: &str) -> Option<glib::Variant> {
    let now = CONTROLS.with_borrow(|c| c.now.clone());
    let loaded = now.is_some();
    Some(match (interface, name) {
        (MPRIS_ROOT, "CanQuit" | "CanRaise" | "HasTrackList") => false.to_variant(),
        (MPRIS_ROOT, "Identity") => crate::core_ui::words::app_name().to_variant(),
        (MPRIS_ROOT, "DesktopEntry") => env!("VEYQ_APP_ID").to_variant(),
        (MPRIS_ROOT, "SupportedUriSchemes" | "SupportedMimeTypes") => Vec::<String>::new().to_variant(),
        (MPRIS_PLAYER, "PlaybackStatus") => status_of(now.as_ref()).to_variant(),
        (MPRIS_PLAYER, "Rate" | "MinimumRate" | "MaximumRate" | "Volume") => 1.0f64.to_variant(),
        (MPRIS_PLAYER, "Metadata") => metadata(now.as_ref()),
        (MPRIS_PLAYER, "Position") => now.as_ref().map_or(0i64, |n| n.position_ms * 1000).to_variant(),
        (MPRIS_PLAYER, "CanGoNext" | "CanGoPrevious" | "CanPlay" | "CanPause" | "CanSeek") => loaded.to_variant(),
        (MPRIS_PLAYER, "CanControl") => true.to_variant(),
        _ => return None,
    })
}

/// A method of the MPRIS object, as the core's `Player.*` call it is.
fn method(name: &str, parameters: &glib::Variant) {
    let now = CONTROLS.with_borrow(|c| c.now.clone());
    let call = match name {
        "Next" => Call::PlayerNext,
        "Previous" => Call::PlayerPrevious,
        "Pause" | "Stop" => Call::PlayerPause,
        "Play" => Call::PlayerResume,
        "PlayPause" if now.as_ref().is_some_and(|n| n.playing) => Call::PlayerPause,
        "PlayPause" => Call::PlayerResume,
        "Seek" => {
            let offset = parameters.child_value(0).get::<i64>().unwrap_or(0);
            let at = now.as_ref().map_or(0, |n| n.position_ms) + offset / 1000;
            Call::PlayerSeek { position_ms: at.max(0) }
        }
        "SetPosition" => Call::PlayerSeek { position_ms: (parameters.child_value(1).get::<i64>().unwrap_or(0) / 1000).max(0) },
        // Raise, Quit and OpenUri: not offered (CanRaise, CanQuit false; no URI opens a track)
        _ => return,
    };
    eprintln!("veyq: system controls: {name}");
    bridge::send(call);
}

/// A queue loaded: the app owns [MPRIS_NAME] and serves the MPRIS object until [unbind].
fn bind() {
    if CONTROLS.with_borrow(|c| c.owner.is_some()) {
        return;
    }
    let owner = gio::bus_own_name(
        gio::BusType::Session,
        MPRIS_NAME,
        gio::BusNameOwnerFlags::NONE,
        |connection, _| register(connection),
        |_, name| eprintln!("veyq: system controls: {name} owned"),
        |_, name| eprintln!("veyq: system controls: {name} not owned (no session bus, or another holds it)"),
    );
    CONTROLS.with_borrow_mut(|c| c.owner = Some(owner));
}

fn register(connection: gio::DBusConnection) {
    let info = match gio::DBusNodeInfo::for_xml(MPRIS_XML) {
        Ok(info) => info,
        Err(e) => return eprintln!("veyq: system controls: MPRIS's interfaces did not read: {e}"),
    };
    let mut registered = Vec::new();
    for name in [MPRIS_ROOT, MPRIS_PLAYER] {
        let Some(interface) = info.lookup_interface(name) else { continue };
        let id = connection
            .register_object(MPRIS_PATH, &interface)
            .method_call(|_, _, _, _, method_name, parameters, invocation| {
                method(method_name, &parameters);
                invocation.return_value(None);
            })
            .property(|_, _, _, interface, name| property(interface, name).unwrap_or_else(|| false.to_variant()))
            .build();
        match id {
            Ok(id) => registered.push(id),
            Err(e) => eprintln!("veyq: system controls: {name} was not registered: {e}"),
        }
    }
    CONTROLS.with_borrow_mut(|c| {
        c.connection = Some(connection);
        c.registered = registered;
    });
    changed();
}

/// What plays now, shown on the desktop's controls.
fn show(now: NowPlayingShown) {
    CONTROLS.with_borrow_mut(|c| c.now = Some(now));
    changed();
}

/// The player's properties that changed, told to the bus (`PropertiesChanged`; Position is read, never told).
fn changed() {
    let Some(connection) = CONTROLS.with_borrow(|c| c.connection.clone()) else { return };
    let mut props: HashMap<String, glib::Variant> = HashMap::new();
    for name in ["PlaybackStatus", "Metadata", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek"] {
        if let Some(v) = property(MPRIS_PLAYER, name) {
            props.insert(name.into(), v);
        }
    }
    let body = (MPRIS_PLAYER, props, Vec::<String>::new()).to_variant();
    if let Err(e) = connection.emit_signal(None, MPRIS_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged", Some(&body)) {
        eprintln!("veyq: system controls: a change was not told: {e}");
    }
}

/// The queue stopped: the object goes from the bus and the name is let go, so the desktop lists no player.
fn unbind() {
    let (owner, connection, registered) = CONTROLS.with_borrow_mut(|c| {
        c.now = None;
        (c.owner.take(), c.connection.take(), std::mem::take(&mut c.registered))
    });
    if let Some(connection) = connection {
        for id in registered {
            let _ = connection.unregister_object(id);
        }
    }
    if let Some(owner) = owner {
        gio::bus_unown_name(owner);
        eprintln!("veyq: system controls: cleared");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A crossfade's ramp (ADR 0142 decision 2): steps of at most 50 ms that end at the target; a ramp of no length sets it.
    #[test]
    fn a_ramp_steps_at_most_every_50_ms_and_ends_at_its_target() {
        let up = ramp_steps(0.0, 1.0, 5000);
        assert_eq!(up.len() as i64, 5000 / RAMP_STEP_MS);
        assert_eq!(*up.last().unwrap(), 1.0);
        assert!(up.windows(2).all(|w| w[1] > w[0]), "rising");
        assert!((up[0] - RAMP_STEP_MS as f64 / 5000.0).abs() < 1e-9, "the first step one step's share");
        let down = ramp_steps(0.8, 0.0, 1000);
        assert_eq!(down.len(), 25);
        assert_eq!(*down.last().unwrap(), 0.0);
        assert_eq!(ramp_steps(0.3, 0.7, 0), vec![0.7]);
        assert_eq!(ramp_steps(0.3, 0.7, 10), vec![0.7], "shorter than a step: one step");
    }

    /// The desktop's controls: the status follows the player, a track's id is an object path whatever the core's id holds.
    #[test]
    fn the_mpris_status_and_track_id_follow_what_plays() {
        let now = |playing| NowPlayingShown {
            track_id: "m-1/Ä b".into(),
            title: "Song".into(),
            album: Some("Holidays".into()),
            route: None,
            playing,
            position_ms: 1500,
            duration_ms: 60_000,
            position_text: "0:02".into(),
            duration_text: "1:00".into(),
        };
        assert_eq!(status_of(None), "Stopped");
        assert_eq!(status_of(Some(&now(true))), "Playing");
        assert_eq!(status_of(Some(&now(false))), "Paused");
        assert_eq!(track_path("m-1/Ä b"), "/app/veyq/track/m_1___b");
        assert!(glib::variant::ObjectPath::try_from(track_path("")).is_ok());
        let m = metadata(Some(&now(true)));
        let dict = glib::VariantDict::new(Some(&m));
        assert_eq!(dict.lookup::<String>("xesam:title").unwrap().as_deref(), Some("Song"));
        assert_eq!(dict.lookup::<String>("xesam:album").unwrap().as_deref(), Some("Holidays"));
        assert_eq!(dict.lookup::<i64>("mpris:length").unwrap(), Some(60_000_000));
        assert!(MPRIS_NAME.starts_with("org.mpris.MediaPlayer2.") && MPRIS_NAME.ends_with(env!("VEYQ_APP_ID")));
        gio::DBusNodeInfo::for_xml(MPRIS_XML).expect("MPRIS's interfaces read");
    }
}
