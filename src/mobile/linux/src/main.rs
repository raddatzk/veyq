//! The veyq app on Linux: the window, the core started behind it, and the screen the core's session says — opening,
//! the sign-in, or the shell (ADR 0123's Consequences, the Linux app). Each screen draws the core's state and calls its
//! actions; nothing else.
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;
use veyq::bridge::wire::{Answer, Call, Config, Gate, Method, StaySignedIn, Update, Watched};
use veyq::bridge::{self, DeviceStore, Start};
use veyq::shell::Shell;
use veyq::manage::Manage;
use veyq::sign_in::{SignIn, Unlock};
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

/// The window's faces: what shows while the core opens or refuses, the sign-in, the unlock, the shell; and "Manage
/// servers" over the shell.
struct Screens {
    window: adw::ApplicationWindow,
    status: adw::ToolbarView,
    status_page: adw::StatusPage,
    sign_in: SignIn,
    unlock: Unlock,
    shell: Rc<Shell>,
    manage: Rc<Manage>,
}

impl Screens {
    fn face(&self, w: &impl IsA<gtk::Widget>) {
        if self.window.content().as_ref() != Some(w.upcast_ref()) {
            self.window.set_content(Some(w));
        }
    }

    fn gate(&self, gate: &Gate, stay: Option<StaySignedIn>) {
        if !matches!(gate, Gate::Signed { .. }) {
            self.manage.close();
        }
        match gate {
            Gate::Starting => self.face(&self.status),
            Gate::SignIn { working, error, .. } => {
                self.sign_in.show(gate, stay);
                self.face(&self.sign_in.page);
                if !working
                    && error.is_none()
                    && let Some(dev) = dev_login::take()
                {
                    self.sign_in.dev(dev);
                }
            }
            Gate::Unlock { record, working, error } => {
                self.unlock.show(gate);
                self.face(&self.unlock.page);
                if record.method == Method::Device
                    && !working
                    && error.is_none()
                    && let Some(password) = dev_login::take_unlock()
                {
                    bridge::send(Call::SessionUnlock { password });
                }
            }
            Gate::Signed { switching, .. } => {
                self.shell.switching(*switching);
                self.face(&self.shell.split);
            }
            // Only the share extension reaches it (ADR 0129), which this app has none of: said on stderr, the window
            // stays where it is.
            Gate::NeedsApp => eprintln!("veyq: the session is at a gate this app does not draw: NeedsApp"),
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
    let manage = Manage::new(&window);
    // the switcher's "Manage servers"
    let open_manage = gtk::gio::SimpleAction::new("manage", None);
    let m = manage.clone();
    open_manage.connect_activate(move |_, _| m.open());
    window.add_action(&open_manage);
    let screens = Rc::new(Screens { window: window.clone(), status, status_page, sign_in: SignIn::new(), unlock: Unlock::new(), shell, manage });
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
    let (Some((session_watch, gates)), Some((shell_watch, shells)), Some((manage_watch, managed))) =
        (bridge::watch(&Watched::Session), bridge::watch(&Watched::Shell), bridge::watch(&Watched::Manage))
    else {
        eprintln!("veyq: the core refused to show the session, the shell or Manage servers");
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
                Ok(Update::Session { gate, stay_signed_in }) => {
                    eprintln!("veyq: session {}{}", kind(&gate), stay_signed_in.map(|s| format!(", stay signed in: {s:?}")).unwrap_or_default());
                    s.gate(&gate, stay_signed_in);
                }
                Ok(_) => {}
                Err(e) => eprintln!("veyq: a session update did not read: {:?}", e.0),
            }
        }
    });
    let m = screens.manage.clone();
    glib::spawn_future_local(async move {
        let _watch = manage_watch;
        while let Ok(update) = managed.recv().await {
            match update {
                Ok(Update::Manage { servers }) => {
                    if let Some(servers) = &servers {
                        // what this device trusts, for the pass's log: each server's method, no name of a person
                        let methods: Vec<String> = servers.iter().map(|s| format!("{:?}", s.trusted)).collect();
                        eprintln!("veyq: manage servers: {}", methods.join(", "));
                    }
                    m.show(servers);
                }
                Ok(_) => {}
                Err(e) => eprintln!("veyq: a Manage servers update did not read: {:?}", e.0),
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
