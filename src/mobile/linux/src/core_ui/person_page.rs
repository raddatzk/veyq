//! A person's profile on Linux (ADR 0144 decisions 11–13; Windows' `PersonPage.cs`): a page of the shell while its state
//! names a person (`Shell.person`), GNOME's way — core's frame: their avatar at its largest, their name, the `~username`
//! line under a real name, a former member's hint and the safety number ([crate::core_ui::safety]); then each
//! application's section as it registered one ([crate::core_ui::registry::PersonSection], Memories' "Memories with
//! {name}"), drawn from the same state. Every state is the core's (`Person.Open`, `Watched.Person`).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::PersonShown, wire::Update, wire::Watched};
use crate::core_ui::registry::{registry, PersonSection};
use crate::core_ui::{avatars, words};

/// A person's page on screen: opened in the core when made, closed when dropped.
pub struct PersonPage {
    pub user_id: String,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::ScrolledWindow,
    avatar_slot: gtk::Box,
    name: gtk::Label,
    username: gtk::Label,
    hint: gtk::Label,
    /// Each application's section, under the frame, in the order the applications registered.
    sections: RefCell<Vec<Box<dyn PersonSection>>>,
    id: Cell<Option<i64>>,
    drawn_avatar: RefCell<Option<(String, bool)>>,
    watch: RefCell<Option<Watch>>,
    /// The safety number's place below the name (ADR 0148 decision 3), filled once the person is shown; [mine]: one's own.
    safety_slot: gtk::Box,
    mine: bool,
    safety_drawn: Cell<bool>,
}

impl PersonPage {
    /// Person [user_id]'s page over [window]; [mine]: the signed-in member's own.
    pub fn open(user_id: &str, mine: bool, window: &gtk::Window) -> Self {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
        let avatar_slot = gtk::Box::builder().halign(gtk::Align::Center).build();
        let name = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).build();
        name.add_css_class("title-1");
        name.set_widget_name("person-name");
        let username = gtk::Label::builder().visible(false).build();
        username.add_css_class("dim-label");
        let hint = gtk::Label::builder().label(words::people_former_member_hint()).visible(false).build();
        hint.add_css_class("dim-label");
        let safety_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        for w in [avatar_slot.upcast_ref::<gtk::Widget>(), name.upcast_ref(), username.upcast_ref(), hint.upcast_ref(), safety_slot.upcast_ref()] {
            column.append(w);
        }
        let sections: Vec<Box<dyn PersonSection>> = registry().applications().iter().filter_map(|a| a.person_section).map(|make| make(window)).collect();
        for s in &sections {
            column.append(&s.widget());
        }
        let clamp = adw::Clamp::builder().maximum_size(720).child(&column).build();
        let root = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        let inner = Rc::new(Inner {
            root,
            avatar_slot,
            name,
            username,
            hint,
            sections: RefCell::new(sections),
            id: Cell::new(None),
            drawn_avatar: RefCell::new(None),
            watch: RefCell::new(None),
            safety_slot,
            mine,
            safety_drawn: Cell::new(false),
        });
        let (weak, user) = (Rc::downgrade(&inner), user_id.to_owned());
        glib::spawn_future_local(async move {
            let person = match bridge::call(&Call::PersonOpen { user_id: user }).await.map(|(a, _)| a) {
                Ok(Answer::PersonOpened { person }) => person,
                other => return eprintln!("veyq: Person.Open answered {other:?}"),
            };
            let Some(inner) = weak.upgrade() else { return bridge::send(Call::PersonClose { person }) };
            inner.id.set(Some(person));
            eprintln!("veyq: person's page open");
            let Some((watch, updates)) = bridge::watch(&Watched::Person { person }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            while let Ok(update) = updates.recv().await {
                let Some(inner) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Person { shown: Some(s), .. }) => inner.draw(person, &s),
                    Ok(Update::Person { shown: None, .. }) => {
                        inner.id.set(None);
                        return;
                    }
                    Ok(_) => {}
                    Err(e) => eprintln!("veyq: a person update did not read: {:?}", e.0),
                }
            }
        });
        Self { user_id: user_id.to_owned(), inner }
    }

    pub fn widget(&self) -> &gtk::ScrolledWindow {
        &self.inner.root
    }
}

impl Drop for PersonPage {
    fn drop(&mut self) {
        // the sections first: what they opened in the core (a person's map) closes before the person does
        self.inner.sections.borrow_mut().clear();
        self.inner.watch.borrow_mut().take();
        if let Some(person) = self.inner.id.take() {
            bridge::send(Call::PersonClose { person });
        }
    }
}

impl Inner {
    fn draw(&self, person: i64, s: &PersonShown) {
        let name = s.name.clone().unwrap_or_else(|| if s.former { words::memory_participants_former() } else { words::people_unknown_member() });
        eprintln!("veyq: person shown: named {}, former {}, picture {}, username {}, sections {:?}", s.name.is_some(), s.former, s.picture,
            s.username.is_some(), s.sections);
        self.name.set_label(&name);
        self.username.set_visible(s.username.is_some());
        self.username.set_label(s.username.as_deref().unwrap_or_default());
        self.hint.set_visible(s.former);
        // the safety number: one's own to show, a member's to compare; a former member has none here
        if !s.former && !self.safety_drawn.replace(true) {
            self.safety_slot.append(&crate::core_ui::safety::section(&s.user_id, self.mine, &name));
        }
        let avatar_key = (name.clone(), s.picture);
        if self.drawn_avatar.replace(Some(avatar_key.clone())) != Some(avatar_key) {
            while let Some(c) = self.avatar_slot.first_child() {
                self.avatar_slot.remove(&c);
            }
            self.avatar_slot.append(&avatars::avatar(&name, s.picture.then_some(s.user_id.as_str()), 128));
        }
        for section in self.sections.borrow().iter() {
            section.draw(person, s);
        }
    }
}
