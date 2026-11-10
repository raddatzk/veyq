//! The veyq app on Linux: the window, the core started behind it (CR core-as-a-native-library Phase 3). What the
//! window draws comes from the core's models (the slice's screens); here it says the core is opening.
use adw::prelude::*;
use gtk::glib;
use veyq::bridge::{self, DeviceStore, Start};
use veyq::bridge::wire::{Answer, Call, Config, Update, Watched};
use veyq::theme::APP_ID;
use veyq::words;

fn main() -> glib::ExitCode {
    words::init();
    veyq::theme::register();
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(activate);
    app.connect_shutdown(|_| bridge::shutdown());
    app.run()
}

fn activate(app: &adw::Application) {
    let status = adw::StatusPage::builder()
        .icon_name(APP_ID)
        .title(words::app_name())
        .description(words::starting())
        .build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&status));
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title(words::app_name())
        .default_width(1100)
        .default_height(750)
        .content(&view)
        .build();
    window.present();

    let store = DeviceStore::open(glib::user_data_dir().join("veyq").join("device-store.json")).unwrap_or_else(|e| {
        eprintln!("veyq: the device store did not open ({e}); this start keeps nothing");
        DeviceStore::in_memory()
    });
    let config = Config { device: glib::host_name().to_string() };
    match bridge::start(&config, store) {
        Start::Started | Start::AlreadyStarted => {}
        Start::NoAes => {
            status.set_description(Some(&words::start_no_aes()));
            return;
        }
        Start::Refused => {
            eprintln!("veyq: the core refused its start");
            return;
        }
    }
    eprintln!("veyq: the core started");
    let Some((watch, updates)) = bridge::watch(&Watched::Session) else {
        eprintln!("veyq: the core refused to show the session");
        return;
    };
    glib::spawn_future_local(async move {
        // The way in, once per process: the trusted accounts resume, or the sign-in shows.
        match bridge::call(&Call::SessionStart).await {
            Ok(Answer::Done) => {}
            other => eprintln!("veyq: the start answered {other:?}"),
        }
    });
    glib::spawn_future_local(async move {
        let _watch = watch;
        while let Ok(update) = updates.recv().await {
            match update {
                // Only the gate's kind: what it holds is the slice's to draw.
                Ok(Update::Session { gate }) => {
                    let kind = serde_json::to_value(&gate).ok().and_then(|v| v["type"].as_str().map(str::to_owned));
                    eprintln!("veyq: session {}", kind.unwrap_or_default());
                }
                Ok(_) => {}
                Err(e) => eprintln!("veyq: an update did not read: {:?}", e.0),
            }
        }
    });
}
