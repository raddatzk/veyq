//! The core UI (ADR 0154 decision 3; the web's `src/web/src/core`): what every application hangs onto, so they all work and
//! look alike — the words ([words], [said]), the theme ([theme]), the creating action ([create_action]), the members'
//! pictures ([avatars]), [sign_in] with the unlock, [shell] with [home] and its [tiles], the bar's places ([places]), a
//! node's [header] with its [move_sheet], [share_sheet], [report] and [cover] and a space's [trash], the registry the
//! applications fill ([registry]), a received link opened as its guest ([received]) or an invite's [join] and a kept link's page
//! ([bookmark_page]) and dialog ([bookmark_create]), a person's profile ([person_page], each application's section
//! registered), the search in the shell's sidebar ([search]), [manage] ("Manage servers", from the switcher), the account's
//! Settings ([account_settings], from the sidebar) with this install's [appearance] and language, the space's [whos_who], [members] with its [invites], [scope_acts] (the icon header, badges, links and lock it shares with a group's page) and [terms] (and the terms gate in place of the shell) with its device linking ([device_link], from the sign-in too), its map
//! services ([map_services]) over the core's raster map ([raster_map]), the account's flows ([account_flows],
//! [profile_edit], [safety]), the uploads ([uploads]) with drag and drop ([drop]) and a download's import
//! ([import_dialog]), a running save's banner ([saving]), a picture held at the core's edge ([picture]), a medium played
//! from the core's reader ([player] over [reader_stream]), a PDF's page drawn from memory ([pdf_render]) with its text
//! selected and copied ([pdf_text]) and read by a screen reader ([pdf_reading]) — a Depot file's and an Expenses receipt's —, a picked
//! medium's tiers ([media_tiers]), the account's music — its bar ([music_bar]), its decks and the desktop's media controls
//! ([music_host]) —, a text field's rule ([typed]), a QR scan's camera ([qr_camera]), an Emergency Kit's pages drawn for
//! the core ([kit_drawing]), and, in a debug build, [dev_login] and the harnesses [dev_scan] and [dev_settings]. It names
//! no application's module: what an application draws reaches it through [registry].
pub mod account_flows;
pub mod account_settings;
pub mod appearance;
pub mod avatars;
pub mod donation;
pub mod bookmark_create;
pub mod bookmark_page;
pub mod cover;
pub mod create_action;
pub mod dev_login;
pub mod dev_scan;
pub mod dev_settings;
pub mod device_link;
pub mod drop;
pub mod groups;
pub mod header;
pub mod home;
pub mod import_dialog;
pub mod invites;
pub mod join;
pub mod kit_drawing;
pub mod manage;
pub mod map_services;
pub mod media_tiers;
pub mod members;
pub mod move_sheet;
pub mod music_bar;
pub mod music_host;
pub mod person_page;
pub mod pdf_reading;
pub mod pdf_render;
pub mod pdf_text;
pub mod picture;
pub mod places;
pub mod player;
pub mod profile_edit;
pub mod qr_camera;
pub mod raster_map;
pub mod reader_stream;
pub mod received;
pub mod registry;
pub mod report;
pub mod safety;
pub mod scope_acts;
pub mod said;
pub mod saving;
pub mod search;
pub mod selection;
pub mod share_sheet;
pub mod shell;
pub mod sign_in;
pub mod sign_in_entries;
pub mod theme;
pub mod terms;
pub mod tiles;
pub mod trash;
pub mod typed;
pub mod uploads;
pub mod trust_reset;
pub mod whos_who;
pub mod words;
