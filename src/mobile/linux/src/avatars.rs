//! The members' pictures (the CR's D5 and D6): when they are read is the core's (`AvatarsModel`); the shell's state
//! names the user ids whose picture the core holds (`ShellShown.pictures`), and each is asked for once with
//! `Avatar.Picture`, its bytes beside the answer (D1.1). The host keeps them per tenant open and asks again whenever that
//! set of ids changes, as the bridge's protocol says; the Pot's update carries the same set, so the shell's alone feeds
//! it. A person is drawn as libadwaita draws one (`AdwAvatar`): their picture where it is held, their initials
//! otherwise — GTK decodes the picture (`gdk::Texture::from_bytes`).
use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::Call};

/// What the host holds of the pictures and what it asks for — no widget, so a test reads it.
#[derive(Debug)]
pub struct Held<T> {
    tenant: Option<String>,
    listed: Option<BTreeSet<String>>,
    pictures: HashMap<String, T>,
    /// Bumped on every ask, so an answer to an earlier one is told apart.
    round: u64,
}

impl<T> Default for Held<T> {
    fn default() -> Self {
        Self { tenant: None, listed: None, pictures: HashMap::new(), round: 0 }
    }
}

/// What a new list of ids asks of the host.
#[derive(Debug, PartialEq, Eq)]
pub struct Asked {
    /// The ids to ask the core for, in this round.
    pub ask: Vec<String>,
    /// The ids whose picture is gone: their people show their initials again.
    pub gone: Vec<String>,
    pub round: u64,
}

impl<T> Held<T> {
    /// The core says [ids] have a picture in [tenant]. Another tenant drops everything held; a set that differs from
    /// the last one is asked for whole (a picture may have changed with it), the same set again asks nothing.
    pub fn listed(&mut self, tenant: &str, ids: &[String]) -> Asked {
        let ids: BTreeSet<String> = ids.iter().cloned().collect();
        let mut gone = Vec::new();
        if self.tenant.as_deref() != Some(tenant) {
            gone.extend(self.pictures.drain().map(|(id, _)| id));
            self.tenant = Some(tenant.to_owned());
            self.listed = None;
        }
        if self.listed.as_ref() == Some(&ids) {
            return Asked { ask: Vec::new(), gone, round: self.round };
        }
        let dropped: Vec<String> = self.pictures.keys().filter(|id| !ids.contains(*id)).cloned().collect();
        for id in &dropped {
            self.pictures.remove(id);
        }
        gone.extend(dropped);
        gone.sort();
        self.round += 1;
        let ask = ids.iter().cloned().collect();
        self.listed = Some(ids);
        Asked { ask, gone, round: self.round }
    }

    /// The core's answer for [id], asked in [round]: kept unless the tenant or the set changed since, or it did not
    /// decode (None) — then what was held stays. True when it is the one shown now.
    pub fn arrived(&mut self, round: u64, id: &str, picture: Option<T>) -> bool {
        let current = round == self.round && self.listed.as_ref().is_some_and(|l| l.contains(id));
        match picture {
            Some(p) if current => {
                self.pictures.insert(id.to_owned(), p);
                true
            }
            _ => false,
        }
    }

    pub fn get(&self, id: &str) -> Option<&T> {
        self.pictures.get(id)
    }
}

/// The pictures of the tenant open now, and every avatar drawn for a user id — set again when its picture arrives or
/// goes.
#[derive(Default)]
struct Pictures {
    held: Held<gdk::Texture>,
    shown: Vec<(glib::WeakRef<adw::Avatar>, String)>,
}

thread_local! {
    static PICTURES: RefCell<Pictures> = RefCell::default();
}

/// The shell's list of ids with a picture in [tenant] (`ShellShown.pictures`): asks for each when the set changed.
pub fn listed(tenant: &str, ids: &[String]) {
    let asked = PICTURES.with_borrow_mut(|p| p.held.listed(tenant, ids));
    for id in &asked.gone {
        refresh(id);
    }
    for id in asked.ask {
        let round = asked.round;
        glib::spawn_future_local(async move {
            let picture = match bridge::call(&Call::AvatarPicture { user_id: id.clone() }).await {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(texture) => Some(texture),
                    Err(e) => {
                        eprintln!("veyq: a member's picture did not decode: {e}");
                        None
                    }
                },
                other => {
                    eprintln!("veyq: Avatar.Picture answered {:?}", other.map(|(answer, _)| answer));
                    None
                }
            };
            if PICTURES.with_borrow_mut(|p| p.held.arrived(round, &id, picture)) {
                refresh(&id);
            }
        });
    }
}

/// A person as libadwaita draws one: [name]'s initials, or the picture of [user_id] once the core gave it.
pub fn avatar(name: &str, user_id: Option<&str>, size: i32) -> adw::Avatar {
    let avatar = adw::Avatar::new(size, Some(name), true);
    show(&avatar, user_id);
    avatar
}

/// [avatar] shows the picture of [user_id] (none: its initials), now and whenever that picture arrives or goes.
pub fn show(avatar: &adw::Avatar, user_id: Option<&str>) {
    PICTURES.with_borrow_mut(|p| {
        p.shown.retain(|(a, _)| a.upgrade().is_some_and(|a| &a != avatar));
        let picture = user_id.and_then(|id| p.held.get(id));
        avatar.set_custom_image(picture);
        if let Some(id) = user_id {
            p.shown.push((avatar.downgrade(), id.to_owned()));
        }
    });
}

fn refresh(id: &str) {
    PICTURES.with_borrow_mut(|p| {
        p.shown.retain(|(a, _)| a.upgrade().is_some());
        let picture = p.held.get(id).cloned();
        for (a, _) in p.shown.iter().filter(|(_, shown)| shown == id) {
            if let Some(a) = a.upgrade() {
                a.set_custom_image(picture.as_ref());
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn a_set_is_asked_for_once_and_again_only_when_it_changes() {
        let mut held = Held::<&str>::default();
        let first = held.listed("t1", &ids(&["b", "a"]));
        assert_eq!(first.ask, ids(&["a", "b"]));
        assert!(held.arrived(first.round, "a", Some("A")));
        assert!(held.arrived(first.round, "b", Some("B")));
        // the same set again, in another order: nothing asked
        assert_eq!(held.listed("t1", &ids(&["a", "b"])).ask, Vec::<String>::new());
        // a member's picture set: the whole set asked again, the others kept meanwhile
        let second = held.listed("t1", &ids(&["a", "b", "c"]));
        assert_eq!((second.ask, second.gone), (ids(&["a", "b", "c"]), vec![]));
        assert_eq!(held.get("a"), Some(&"A"));
        // a picture removed: gone at once, its person shows the initials
        let third = held.listed("t1", &ids(&["a", "c"]));
        assert_eq!(third.gone, ids(&["b"]));
        assert_eq!(held.get("b"), None);
    }

    #[test]
    fn another_tenant_drops_what_was_held_and_a_late_answer_is_not_kept() {
        let mut held = Held::<&str>::default();
        let first = held.listed("t1", &ids(&["a"]));
        assert!(held.arrived(first.round, "a", Some("A")));
        let other = held.listed("t2", &ids(&["a", "x"]));
        assert_eq!((other.ask, other.gone), (ids(&["a", "x"]), ids(&["a"])));
        assert_eq!(held.get("a"), None, "t1's picture of a is not t2's");
        // an answer to t1's round arriving now is t1's: dropped
        assert!(!held.arrived(first.round, "a", Some("A of t1")));
        assert_eq!(held.get("a"), None);
        // nor is an answer for an id no longer listed, nor one that did not decode
        assert!(!held.arrived(other.round, "z", Some("Z")));
        assert!(!held.arrived(other.round, "x", None));
        assert!(held.arrived(other.round, "x", Some("X")));
        // and back to t1: asked anew, though its set is the one it had
        assert_eq!(held.listed("t1", &ids(&["a"])).ask, ids(&["a"]));
    }

    /// The web writes a member's picture as WebP, which GTK decodes only through glycin's loaders (`glycin-loaders`,
    /// which Fedora's gtk4 pulls in; a Flatpak's runtime must carry them): without them every person shows their
    /// initials, and only stderr says why.
    #[test]
    fn a_picture_as_the_web_writes_it_decodes() {
        // a 1×1 lossless WebP
        let webp: &[u8] = &[
            0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0x00,
            0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0x00,
        ];
        let texture = gdk::Texture::from_bytes(&glib::Bytes::from_static(webp)).expect("a WebP decodes");
        assert_eq!((texture.width(), texture.height()), (1, 1));
    }
}
