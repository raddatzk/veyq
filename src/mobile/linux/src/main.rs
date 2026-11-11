//! The veyq app on Linux: the window, the core started behind it, and the screen the core's session says — opening,
//! the sign-in, or the shell (ADR 0123's Consequences, the Linux app). Each screen draws the core's state and calls its
//! actions; nothing else.
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;
use veyq::bridge::wire::{Answer, Call, Config, Gate, Update, Watched};
use veyq::bridge::{self, DeviceStore, Start};
use veyq::shell::Shell;
use veyq::sign_in::SignIn;
use veyq::theme::APP_ID;
use veyq::{dev_login, words};

fn main() -> glib::ExitCode {
    words::init();
    veyq::theme::register();
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(activate);
    app.connect_shutdown(|_| bridge::shutdown());
    app.run()
}

/// The window's three faces: what shows while the core opens or refuses, the sign-in, the shell.
struct Screens {
    window: adw::ApplicationWindow,
    status: adw::ToolbarView,
    status_page: adw::StatusPage,
    sign_in: SignIn,
    shell: Rc<Shell>,
}

impl Screens {
    fn face(&self, w: &impl IsA<gtk::Widget>) {
        if self.window.content().as_ref() != Some(w.upcast_ref()) {
            self.window.set_content(Some(w));
        }
    }

    fn gate(&self, gate: &Gate) {
        match gate {
            Gate::Starting => self.face(&self.status),
            Gate::SignIn { working, error, .. } => {
                if !working
                    && error.is_none()
                    && let Some(call) = dev_login::take()
                {
                    bridge::send(call);
                }
                self.sign_in.show(gate);
                self.face(&self.sign_in.page);
            }
            Gate::Signed { switching, .. } => {
                self.shell.switching(*switching);
                self.face(&self.shell.split);
            }
            // Neither reaches this slice: no share extension (NeedsApp), no trusted device without the Secret Service
            // (Unlock, the VM slice). Said on stderr, the window stays where it is.
            other => eprintln!("veyq: the session is at a gate this slice does not draw: {other:?}"),
        }
    }
}

fn activate(app: &adw::Application) {
    veyq::theme::use_app_icon();
    let status_page = adw::StatusPage::builder().icon_name(APP_ID).title(words::app_name()).description(words::starting()).build();
    let status = adw::ToolbarView::new();
    status.add_top_bar(&adw::HeaderBar::new());
    status.set_content(Some(&status_page));
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title(words::app_name())
        .default_width(1100)
        .default_height(750)
        .width_request(360)
        .height_request(294)
        .content(&status)
        .build();
    let shell = Shell::new(&window);
    let screens = Rc::new(Screens { window: window.clone(), status, status_page, sign_in: SignIn::new(), shell });
    window.present();

    let store = DeviceStore::open(glib::user_data_dir().join("veyq").join("device-store.json")).unwrap_or_else(|e| {
        eprintln!("veyq: the device store did not open ({e}); this start keeps nothing");
        DeviceStore::in_memory()
    });
    // no window owns a prompt here: the Secret Service asks nothing of its own (the CR device-trust-on-linux-and-windows, D2)
    let config = Config { device: glib::host_name().to_string(), window: 0, prompt: String::new() };
    match bridge::start(&config, store) {
        Start::Started | Start::AlreadyStarted => {}
        Start::NoAes => {
            screens.status_page.set_description(Some(&words::start_no_aes()));
            return;
        }
        Start::Refused => {
            eprintln!("veyq: the core refused its start");
            return;
        }
    }
    eprintln!("veyq: the core started");
    let (Some((session_watch, gates)), Some((shell_watch, shells))) = (bridge::watch(&Watched::Session), bridge::watch(&Watched::Shell)) else {
        eprintln!("veyq: the core refused to show the session or the shell");
        return;
    };
    glib::spawn_future_local(async move {
        // The way in, once per process: the trusted accounts resume, or the sign-in shows.
        match bridge::call(&Call::SessionStart).await.map(|(answer, _)| answer) {
            Ok(Answer::Done) => {}
            other => eprintln!("veyq: the start answered {other:?}"),
        }
    });
    let s = screens.clone();
    glib::spawn_future_local(async move {
        let _watch = session_watch;
        while let Ok(update) = gates.recv().await {
            match update {
                Ok(Update::Session { gate, .. }) => {
                    eprintln!("veyq: session {}", kind(&gate));
                    s.gate(&gate);
                }
                Ok(_) => {}
                Err(e) => eprintln!("veyq: a session update did not read: {:?}", e.0),
            }
        }
    });
    glib::spawn_future_local(async move {
        let _watch = shell_watch;
        while let Ok(update) = shells.recv().await {
            match update {
                Ok(Update::Shell { state: Some(state), shown: Some(shown) }) => screens.shell.show(state, shown),
                Ok(_) => {}
                Err(e) => eprintln!("veyq: a shell update did not read: {:?}", e.0),
            }
        }
    });
}

/// A gate's kind alone, for stderr (its fields are no one's business there).
fn kind(gate: &Gate) -> String {
    serde_json::to_value(gate).ok().and_then(|v| v["type"].as_str().map(str::to_owned)).unwrap_or_default()
}
