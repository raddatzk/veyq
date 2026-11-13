//! Debug builds only — the QR camera's test harness (the QR CR, phase 5), reached without a sign-in, as Android's and
//! Apple's `DevScan`: started with `VEYQ_DEV_SCAN=1`, the window shows a [ScanView] alone over a scan of kind `SCHEME`
//! (any veyq code, found as its scheme alone); with `VEYQ_DEV_CAMERA=<image>` as well, that image is the camera. Its states
//! go to stderr (`veyq-scan: state …`); the scheme is all that is shown, never the code's text. A release build has no such
//! door.
use std::rc::Rc;

use gtk::prelude::*;

use crate::bridge::wire::{ScanKind, ScanShown, ScanState};
use crate::qr_camera::ScanView;

/// Whether the harness was asked for; false in a release build.
pub fn requested() -> bool {
    #[cfg(debug_assertions)]
    {
        std::env::var("VEYQ_DEV_SCAN").is_ok_and(|v| v == "1")
    }
    #[cfg(not(debug_assertions))]
    false
}

/// The harness's view: the camera, then what the scan came to as an icon (and a found code's scheme).
pub fn view() -> Rc<ScanView> {
    eprintln!("veyq-scan: the scan harness (debug build)");
    ScanView::open(ScanKind::Scheme, None, other, |shown| match (&shown.state, &shown.found) {
        (ScanState::Found, Some(scheme)) => eprintln!("veyq-scan: state Found({scheme})"),
        (state, _) => eprintln!("veyq-scan: state {state:?}"),
    })
}

fn other(shown: &ScanShown) -> Option<gtk::Widget> {
    let icon = match shown.state {
        ScanState::Found => "object-select-symbolic",
        ScanState::NoCamera => "camera-photo-symbolic",
        ScanState::Denied => "dialog-warning-symbolic",
        _ => return None,
    };
    let page = adw::StatusPage::builder().icon_name(icon).vexpand(true).build();
    // the scheme as the core named it: no word of ours
    if let Some(scheme) = &shown.found {
        page.set_title(scheme);
    }
    Some(page.upcast())
}
