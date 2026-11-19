//! The veyq app on Linux (ADR 0136 decision 4; ADR 0123's Consequences, the Linux app): every rule is the core's, reached
//! through libveyq.so ([bridge]); this crate draws and holds the device — the words ([words], [said]), the theme
//! ([theme]), the creating action ([create_action]), the members' pictures ([avatars]) and one module per screen:
//! [sign_in] with the unlock, [shell] with [home], [pot] with its [pot_settings], a file's page ([file_page], a PDF's text selected by [pdf_text], a medium played from [reader_stream] by [player]), a Memory's page ([memory_page]) with its [banner], its participants and capture time ([curation]) and its [lightbox], a person's profile ([person_page]), its Music view ([music_view]) and a picked medium's tiers ([media_tiers]), the account's music — its bar ([music_bar]), its decks and the desktop's media controls ([music_host]) —, a drop box's ([drop_box]), [entry_editor] (a text field's rule, [typed]), a node's [header] with its [move_sheet], [share_sheet], [report] and [cover] and a space's [trash], a received link opened as its guest ([received]) and a kept link's page ([bookmark_page]) and dialog ([bookmark_create]), New pot ([pot_create]), the search in the shell's sidebar ([search]), [manage] ("Manage
//! servers", from the switcher), the account's Settings ([account_settings], from the sidebar) with its device linking ([device_link], from the sign-in too), the uploads ([uploads]) with drag and drop ([drop]), a QR scan's camera ([qr_camera]), an Emergency Kit's pages drawn for the core ([kit_drawing]), and, in a debug build, [dev_login] and
//! the harnesses [dev_scan] and [dev_settings].
pub mod account_flows;
pub mod account_settings;
pub mod avatars;
pub mod banner;
pub mod bookmark_create;
pub mod bookmark_page;
pub mod contributions;
pub mod cover;
pub mod bridge;
pub mod create_action;
pub mod curation;
pub mod dev_login;
pub mod dev_scan;
pub mod dev_settings;
pub mod device_link;
pub mod drop;
pub mod drop_box;
pub mod entry_editor;
pub mod entry_history;
pub mod file_page;
pub mod gallery_filter;
pub mod header;
pub mod home;
pub mod kit_drawing;
pub mod lightbox;
pub mod manage;
pub mod map_services;
pub mod media_tiers;
pub mod memories_map;
pub mod memory_facts;
pub mod memory_map_view;
pub mod memory_page;
pub mod move_sheet;
pub mod music_bar;
pub mod music_host;
pub mod music_view;
pub mod player;
pub mod person_page;
pub mod pdf_text;
pub mod pot;
pub mod pot_create;
pub mod pot_settings;
pub mod profile_edit;
pub mod qr_camera;
pub mod raster_map;
pub mod reader_stream;
pub mod received;
pub mod receipt_viewer;
pub mod report;
pub mod safety;
pub mod said;
pub mod search;
pub mod share_sheet;
pub mod shell;
pub mod sign_in;
pub mod theme;
pub mod tiles;
pub mod trash;
pub mod typed;
pub mod uploads;
pub mod words;

/// The process's locale is one for every test thread: a test that sets it holds this while it does.
#[cfg(test)]
static LOCALE: std::sync::Mutex<()> = std::sync::Mutex::new(());
