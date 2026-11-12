//! The entry editor (the web's `EntryEditor.vue` and `EntrySplit.vue`; D5 of the Expenses CR), GNOME's way: an `AdwDialog`
//! holding an `AdwNavigationView` — the form, a split's page, the entry's history. Every rule and the editor's state are
//! the core's `EntryEditor` (`Editor.Open`, watched as `Watched.Editor`): this draws `EditorShown` and sends each edit
//! (`Editor.Change`), which the core applies in the order it was sent.
//!
//! The form is `AdwPreferencesGroup`s of `AdwEntryRow`s and `AdwComboRow`s — the kind, the currency (with search), the
//! category, a person —, the date through a `gtk::Calendar` popover, Save the header bar's suggested action, the delete a
//! destructive row at the foot asked by an `AdwAlertDialog`; a refusal under its field (the row's error style), the
//! rate's note as the currency row's subtitle. A split opens its page: its mode, one row per person (on or off, the value
//! typed in the mode), its hint, "Split equally among all". The form is built anew only when its shape changes (a payer,
//! an item, the kind…), never while typing; a text field is set from the state only by [Typed]'s rule, so what is typed
//! and the cursor stay. A save answers what to do at once (`Editor.Save`: done closes the dialog, a stale save says so
//! and shows the other version); notices are toasts.
//!
//! An entry's receipts are a group of their own: "Add receipt" opens GTK's open dialog for images and PDFs (several at
//! once), whose paths go to the core (`Editor.AddReceipts`: it reads each, at most a receipt's size, and holds what it
//! takes until the save); a kept receipt opens its viewer ([crate::receipt_viewer]), each one is taken off by its button
//! (`Editor.DropReceipt`); what the core refused is said under the group. Nothing of a receipt is read here.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::EditorChange, wire::EditorShown, wire::EditorSplit,
    wire::EntryKind, wire::RateState, wire::SplitMode, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::typed::Typed;
use crate::{said, words};

/// Opens the editor of entry [entry] of Pot [pot] (None: a new entry) over [parent]; [said] takes what a done save or
/// delete says once the dialog closed.
pub fn open(parent: &gtk::Window, pot: i64, entry: Option<&str>, said: &adw::ToastOverlay) {
    let entry = entry.map(str::to_owned);
    let (parent, said) = (parent.clone(), said.clone());
    glib::spawn_future_local(async move {
        let call = Call::EditorOpen { pot, entry: entry.clone(), separator: said::decimal_separator() };
        let editor = match bridge::call(&call).await.map(|(a, _)| a) {
            Ok(Answer::EditorOpened { editor }) => editor,
            other => {
                eprintln!("veyq: Editor.Open answered {other:?}");
                said.add_toast(adw::Toast::new(&words::ed_save_failed()));
                return;
            }
        };
        eprintln!("veyq: entry editor open ({})", if entry.is_some() { "edit" } else { "new" });
        Inner::show(&parent, pot, editor, entry, &said);
    });
}

/// One text field: its editable (an `AdwEntryRow` or a `gtk::Entry`), the row it is in, and what it sent ([Typed]).
struct Field {
    editable: gtk::Editable,
    widget: gtk::Widget,
    typed: Rc<RefCell<Typed>>,
}

impl Field {
    fn new(inner: &Rc<Inner>, editable: &(impl IsA<gtk::Editable> + IsA<gtk::Widget>), change: impl Fn(String) -> EditorChange + 'static) -> Self {
        let typed = Rc::new(RefCell::new(Typed::default()));
        let (w, t) = (Rc::downgrade(inner), typed.clone());
        editable.connect_changed(move |e| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let text = e.text().to_string();
            t.borrow_mut().sent(&text);
            i.edit(change(text));
        });
        Self { editable: editable.clone().upcast(), widget: editable.clone().upcast(), typed }
    }

    /// The field set from [state] where [Typed] says so (the caller holds the drawing flag).
    fn set(&self, state: &str) {
        let focused = self.widget.state_flags().contains(gtk::StateFlags::FOCUS_WITHIN);
        let shown = self.editable.text();
        if let Some(text) = self.typed.borrow_mut().take(focused, &shown, state) {
            self.editable.set_text(&text);
        }
    }
}

/// A combo row's choices by id, in its model's order.
struct Combo {
    row: adw::ComboRow,
    ids: Vec<String>,
}

impl Combo {
    /// A combo row of [choices] (id, label); [search] lets the person type to find one (the currency). Built whole in one
    /// builder: a search or an expression set after the model replaces the row's selection, which says it changed.
    fn new(inner: &Rc<Inner>, title: &str, choices: &[(String, String)], search: bool, change: impl Fn(String) -> EditorChange + 'static) -> Self {
        let labels: Vec<&str> = choices.iter().map(|(_, l)| l.as_str()).collect();
        let row = adw::ComboRow::builder()
            .title(title)
            .enable_search(search)
            .expression(gtk::PropertyExpression::new(gtk::StringObject::static_type(), None::<gtk::Expression>, "string"))
            .model(&gtk::StringList::new(&labels))
            .build();
        let ids: Vec<String> = choices.iter().map(|(id, _)| id.clone()).collect();
        let (w, by) = (Rc::downgrade(inner), ids.clone());
        row.connect_selected_notify(move |r| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            if let Some(id) = by.get(r.selected() as usize) {
                i.edit(change(id.clone()));
            }
        });
        Self { row, ids }
    }

    fn select(&self, id: &str) {
        if let Some(at) = self.ids.iter().position(|i| i == id)
            && self.row.selected() != at as u32
        {
            self.row.set_selected(at as u32);
        }
    }
}

/// The form's widgets, kept while its shape stays.
struct Form {
    shape: String,
    kind: Combo,
    title: Field,
    title_row: adw::EntryRow,
    date: adw::ActionRow,
    calendar: gtk::Calendar,
    category: Option<Combo>,
    currency: Combo,
    rate: Option<(Field, adw::EntryRow)>,
    amount: Option<(Field, adw::EntryRow)>,
    from: Option<Combo>,
    to: Option<Combo>,
    payers: Vec<(Combo, Field, gtk::Button)>,
    add_payer: Option<gtk::Button>,
    total: Option<gtk::Label>,
    items: Vec<(Field, Field, adw::ActionRow)>,
    rest: Option<adw::ActionRow>,
    preview: adw::PreferencesGroup,
    preview_rows: RefCell<Vec<gtk::Widget>>,
    invalid: gtk::Label,
    /// The refusal's label at each place it is said: `main`, `transfer`, `payers`, `item<i>`, `items`, `rest`, `foot`.
    refusals: Vec<(String, gtk::Label)>,
    receipts: adw::PreferencesGroup,
    receipt_rows: RefCell<Vec<gtk::Widget>>,
    /// What the receipts' rows were drawn from: they are built anew only when it changes.
    receipts_drawn: RefCell<String>,
    add_receipt: gtk::Button,
    refused_pick: gtk::Label,
}

/// A split's page while it is shown: [item] (None: the rest's), built anew when its shape changes.
struct SplitPage {
    item: Option<i32>,
    page: adw::NavigationPage,
    view: adw::ToolbarView,
    shape: String,
    folded: gtk::Label,
    modes: Option<adw::ToggleGroup>,
    rows: Vec<(String, gtk::CheckButton, Field, gtk::Label)>,
    hint: gtk::Label,
}

struct Inner {
    pot: i64,
    editor: i64,
    entry: Option<String>,
    dialog: adw::Dialog,
    nav: adw::NavigationView,
    form_page: adw::NavigationPage,
    toasts: adw::ToastOverlay,
    /// The window's toasts: what a done save says once the dialog closed.
    said: adw::ToastOverlay,
    save: gtk::Button,
    cancel: gtk::Button,
    holder: adw::Clamp,
    form: RefCell<Option<Form>>,
    split: RefCell<Option<SplitPage>>,
    /// Set while the state is drawn: a widget's change signal then sends nothing.
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    last: RefCell<Option<EditorShown>>,
}

impl Inner {
    fn show(parent: &gtk::Window, pot: i64, editor: i64, entry: Option<String>, said: &adw::ToastOverlay) {
        let header = adw::HeaderBar::new();
        header.set_show_start_title_buttons(false);
        header.set_show_end_title_buttons(false);
        let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
        let save = gtk::Button::builder().label(words::entry_save()).build();
        save.add_css_class("suggested-action");
        header.pack_start(&cancel);
        header.pack_end(&save);
        let holder = adw::Clamp::builder().maximum_size(600).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&holder).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&header);
        view.set_content(Some(&scroll));
        let title = if entry.is_some() { words::ed_edit_title() } else { words::ed_new_title() };
        let form_page = adw::NavigationPage::new(&view, &title);
        let nav = adw::NavigationView::new();
        nav.add(&form_page);
        let toasts = adw::ToastOverlay::new();
        toasts.set_child(Some(&nav));
        let dialog = adw::Dialog::builder().child(&toasts).content_width(560).content_height(720).title(title.as_str()).build();
        let inner = Rc::new(Inner {
            pot,
            editor,
            entry,
            dialog: dialog.clone(),
            nav: nav.clone(),
            form_page,
            toasts,
            said: said.clone(),
            save: save.clone(),
            cancel: cancel.clone(),
            holder,
            form: RefCell::new(None),
            split: RefCell::new(None),
            drawing: Cell::new(false),
            watch: RefCell::new(None),
            last: RefCell::new(None),
        });
        let w = Rc::downgrade(&inner);
        cancel.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.dialog.close();
            }
        });
        let w = Rc::downgrade(&inner);
        save.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.save();
            }
        });
        let w = Rc::downgrade(&inner);
        nav.connect_popped(move |_, page| {
            if let Some(i) = w.upgrade() {
                let gone = i.split.borrow().as_ref().is_some_and(|s| &s.page == page);
                if gone {
                    i.split.borrow_mut().take();
                }
            }
        });
        // the dialog holds its state while it is open; closed, the core's editor is closed and the watch dropped
        let held = RefCell::new(Some(inner.clone()));
        dialog.connect_closed(move |_| {
            if let Some(i) = held.borrow_mut().take() {
                i.watch.borrow_mut().take();
                bridge::send(Call::EditorClose { editor: i.editor });
                eprintln!("veyq: entry editor closed");
            }
        });
        dialog.present(Some(parent));
        let Some((watch, updates)) = bridge::watch(&Watched::Editor { editor }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        let w = Rc::downgrade(&inner);
        glib::spawn_future_local(follow(w, updates));
    }

    /// One edit sent; the core applies it in the order sent.
    fn edit(&self, change: EditorChange) {
        bridge::send(Call::EditorChange { editor: self.editor, change });
    }

    fn save(self: &Rc<Self>) {
        self.save.set_sensitive(false);
        let w = Rc::downgrade(self);
        let answer = bridge::call(&Call::EditorSave { editor: self.editor });
        glib::spawn_future_local(async move {
            let answered = answer.await.map(|(a, _)| a);
            let Some(i) = w.upgrade() else { return };
            match answered {
                Ok(Answer::EditorSaved { done, notice }) => {
                    eprintln!("veyq: editor save: done {done}, {}", notice.as_ref().map_or("no notice", |n| n.msg.as_str()));
                    if done {
                        if let Some(n) = notice {
                            i.said.add_toast(adw::Toast::new(&said::notice(&n)));
                        }
                        i.dialog.close();
                        return;
                    }
                    if let Some(n) = notice {
                        i.toasts.add_toast(adw::Toast::new(&said::notice(&n)));
                    }
                }
                other => {
                    eprintln!("veyq: Editor.Save answered {other:?}");
                    i.toasts.add_toast(adw::Toast::new(&words::ed_save_failed()));
                }
            }
            i.save.set_sensitive(true);
        });
    }

    /// The delete asked in an `AdwAlertDialog` (`Editor.AskDelete`), then done or cancelled.
    fn ask_delete(self: &Rc<Self>) {
        bridge::send(Call::EditorAskDelete { editor: self.editor });
        let ask = adw::AlertDialog::builder().heading(words::entry_delete()).body(words::entry_delete_confirm()).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::entry_delete())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let w = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let Some(i) = w.upgrade() else { return };
            if response != "delete" {
                bridge::send(Call::EditorCancelAsk { editor: i.editor });
                return;
            }
            let answer = bridge::call(&Call::EditorDelete { editor: i.editor });
            let w = Rc::downgrade(&i);
            glib::spawn_future_local(async move {
                let answered = answer.await.map(|(a, _)| a);
                eprintln!("veyq: editor delete: {answered:?}");
                let Some(i) = w.upgrade() else { return };
                match answered {
                    Ok(Answer::EditorSaved { done: true, .. }) => {
                        i.dialog.close();
                    }
                    Ok(Answer::EditorSaved { notice: Some(n), .. }) => i.toasts.add_toast(adw::Toast::new(&said::notice(&n))),
                    _ => i.toasts.add_toast(adw::Toast::new(&words::ed_save_failed())),
                }
            });
        });
        ask.present(Some(&self.dialog));
    }

    fn draw(self: &Rc<Self>, s: &EditorShown) {
        let title = if s.is_new { words::ed_new_title() } else if s.editable { words::ed_edit_title() } else { words::ed_view_title() };
        self.form_page.set_title(&title);
        self.dialog.set_title(&title);
        self.save.set_visible(s.editable);
        self.save.set_sensitive(!s.saving);
        self.cancel.set_label(&if s.editable { words::entry_cancel() } else { words::receipts_close() });
        let shape = shape_of(s);
        let rebuild = self.form.borrow().as_ref().is_none_or(|f| f.shape != shape);
        // held while the form is built too: a row's model set sends no change
        self.drawing.set(true);
        if rebuild {
            let form = self.build(s, shape);
            *self.form.borrow_mut() = Some(form);
        }
        if let Some(f) = self.form.borrow().as_ref() {
            update(f, s);
            self.draw_receipts(f, s);
        }
        self.draw_split(s);
        self.drawing.set(false);
        *self.last.borrow_mut() = Some(s.clone());
    }

    /// The form for [s]'s shape: every group, its rows and the places a refusal is said.
    fn build(self: &Rc<Self>, s: &EditorShown, shape: String) -> Form {
        let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
        body.set_margin_top(12);
        body.set_margin_bottom(24);
        body.set_margin_start(12);
        body.set_margin_end(12);
        let mut refusals = Vec::new();
        let refusal = |key: &str, body: &gtk::Box, refusals: &mut Vec<(String, gtk::Label)>| {
            let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
            l.add_css_class("error");
            body.append(&l);
            refusals.push((key.to_owned(), l));
        };
        let invalid = gtk::Label::builder().label(words::ed_invalid()).wrap(true).xalign(0.0).visible(false).build();
        invalid.add_css_class("error");
        body.append(&invalid);
        let people: Vec<(String, String)> = s.people.iter().map(|p| (p.id.clone(), p.name.clone())).collect();

        // what, when, which category, which currency
        let main = adw::PreferencesGroup::new();
        main.set_sensitive(s.editable);
        let kinds = [
            ("EXPENSE".to_owned(), words::ed_expense()),
            ("REFUND".to_owned(), words::ed_refund()),
            ("TRANSFER".to_owned(), words::pot_transfer()),
        ];
        let kind = Combo::new(self, &words::entry_kind(), &kinds, false, |k| EditorChange::EditKind {
            kind: match k.as_str() {
                "REFUND" => EntryKind::Refund,
                "TRANSFER" => EntryKind::Transfer,
                _ => EntryKind::Expense,
            },
        });
        main.add(&kind.row);
        let transfer = s.kind == EntryKind::Transfer;
        let title_row = adw::EntryRow::builder().title(if transfer { words::ed_note() } else { words::entry_title() }).build();
        let title = Field::new(self, &title_row, |text| EditorChange::EditTitle { text });
        main.add(&title_row);
        let calendar = gtk::Calendar::new();
        let pick = gtk::MenuButton::builder()
            .icon_name("x-office-calendar-symbolic")
            .valign(gtk::Align::Center)
            .tooltip_text(words::ed_date())
            .popover(&gtk::Popover::builder().child(&calendar).build())
            .build();
        pick.add_css_class("flat");
        let date = adw::ActionRow::builder().title(words::ed_date()).build();
        date.add_suffix(&pick);
        date.set_activatable_widget(Some(&pick));
        let w = Rc::downgrade(self);
        let popped = pick.clone();
        calendar.connect_day_selected(move |c| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let t = c.date();
            i.edit(EditorChange::EditDay { iso: format!("{:04}-{:02}-{:02}", t.year(), t.month(), t.day_of_month()) });
            popped.popdown();
        });
        main.add(&date);
        let category = (!transfer).then(|| {
            let choices: Vec<(String, String)> = s.categories.iter().map(|c| (c.id.clone(), said::piece(&c.label))).collect();
            let c = Combo::new(self, &words::entry_category(), &choices, false, |id| EditorChange::EditCategory { id });
            main.add(&c.row);
            c
        });
        let codes: Vec<(String, String)> = s.currencies.iter().map(|c| (c.clone(), c.clone())).collect();
        let currency = Combo::new(self, &words::ed_currency(), &codes, true, |code| EditorChange::EditCurrency { code });
        main.add(&currency.row);
        let rate = (s.currency != s.base).then(|| {
            let row = adw::EntryRow::builder().title(words::ed_rate(&s.currency, &s.base)).input_purpose(gtk::InputPurpose::Number).build();
            let f = Field::new(self, &row, |text| EditorChange::EditRateTyped { text });
            main.add(&row);
            (f, row)
        });
        body.append(&main);
        refusal("main", &body, &mut refusals);

        let (mut amount, mut from, mut to, mut total, mut add_payer, mut rest) = (None, None, None, None, None, None);
        let mut payers = Vec::new();
        let mut items = Vec::new();
        if transfer {
            let group = adw::PreferencesGroup::new();
            group.set_sensitive(s.editable);
            let row = adw::EntryRow::builder().title(words::entry_amount()).input_purpose(gtk::InputPurpose::Number).build();
            amount = Some((Field::new(self, &row, |text| EditorChange::EditAmount { text }), row.clone()));
            group.add(&row);
            let f = Combo::new(self, &words::ed_from(), &people, false, |person| EditorChange::EditFrom { person });
            let t = Combo::new(self, &words::ed_to(), &people, false, |person| EditorChange::EditTo { person });
            group.add(&f.row);
            group.add(&t.row);
            from = Some(f);
            to = Some(t);
            body.append(&group);
            refusal("transfer", &body, &mut refusals);
        } else {
            // who paid (a refund: who received), each with the amount; the entry's amount is their sum
            let group = adw::PreferencesGroup::builder().title(if s.kind == EntryKind::Refund { words::ed_received_by() } else { words::entry_paid_by() }).build();
            group.set_sensitive(s.editable);
            for (i, _) in s.payers.iter().enumerate() {
                let index = i as i32;
                let person = Combo::new(self, &words::ed_person(), &people, false, move |person| EditorChange::EditPayer { index, person });
                let entry = gtk::Entry::builder().placeholder_text(words::entry_amount()).width_chars(9).valign(gtk::Align::Center).input_purpose(gtk::InputPurpose::Number).build();
                gtk::prelude::EditableExt::set_alignment(&entry, 1.0);
                let paid = Field::new(self, &entry, move |text| EditorChange::EditPaid { index, text });
                let remove = gtk::Button::builder().icon_name("list-remove-symbolic").valign(gtk::Align::Center).build();
                remove.add_css_class("flat");
                let w = Rc::downgrade(self);
                remove.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    i.edit(EditorChange::EditRemovePayer { index });
                });
                person.row.add_suffix(&entry);
                person.row.add_suffix(&remove);
                group.add(&person.row);
                payers.push((person, paid, remove));
            }
            let add = gtk::Button::builder().label(words::ed_add_payer()).build();
            add.add_css_class("flat");
            let w = Rc::downgrade(self);
            add.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                i.edit(EditorChange::EditAddPayer);
            });
            group.set_header_suffix(Some(&add));
            add_payer = Some(add);
            let sum = adw::ActionRow::builder().title(words::entry_amount()).build();
            let label = gtk::Label::new(None);
            label.add_css_class("heading");
            label.add_css_class("numeric");
            sum.add_suffix(&label);
            group.add(&sum);
            total = Some(label);
            body.append(&group);
            refusal("payers", &body, &mut refusals);

            // the items, each an exact amount with its own split
            for (i, _) in s.items.iter().enumerate() {
                let index = i as i32;
                let group = adw::PreferencesGroup::builder().title(words::ed_item()).build();
                group.set_sensitive(s.editable);
                let remove = gtk::Button::builder().label(words::ed_remove_item()).build();
                remove.add_css_class("flat");
                let w = Rc::downgrade(self);
                remove.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    i.edit(EditorChange::EditRemoveItem { index });
                });
                group.set_header_suffix(Some(&remove));
                let label_row = adw::EntryRow::builder().title(words::ed_item()).build();
                let label = Field::new(self, &label_row, move |text| EditorChange::EditItemLabel { index, text });
                let amount_row = adw::EntryRow::builder().title(words::entry_amount()).input_purpose(gtk::InputPurpose::Number).build();
                let amount = Field::new(self, &amount_row, move |text| EditorChange::EditItemAmount { index, text });
                let split = self.split_row(Some(index));
                group.add(&label_row);
                group.add(&amount_row);
                group.add(&split);
                body.append(&group);
                refusal(&format!("item{i}"), &body, &mut refusals);
                items.push((label, amount, split));
            }
            if s.editable {
                let add = gtk::Button::builder().label(words::ed_add_item()).halign(gtk::Align::Start).build();
                add.add_css_class("flat");
                let w = Rc::downgrade(self);
                add.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    i.edit(EditorChange::EditAddItem);
                });
                body.append(&add);
            }
            refusal("items", &body, &mut refusals);
            if s.rest.is_some() {
                let group = adw::PreferencesGroup::builder().title(if s.rest_is_whole { words::ed_whole() } else { words::ed_rest() }).build();
                let row = self.split_row(None);
                group.add(&row);
                body.append(&group);
                rest = Some(row);
                refusal("rest", &body, &mut refusals);
            }
        }
        let preview = adw::PreferencesGroup::builder().title(words::ed_shares_in(&s.base)).visible(false).build();
        body.append(&preview);

        // the receipts: added through the open dialog, each opened or taken off
        let receipts = adw::PreferencesGroup::builder().title(words::receipts_title()).build();
        let add_receipt = gtk::Button::builder().label(words::receipts_add()).build();
        add_receipt.add_css_class("flat");
        let w = Rc::downgrade(self);
        add_receipt.connect_clicked(move |_| if let Some(i) = w.upgrade() {
            i.pick_receipts();
        });
        receipts.set_header_suffix(Some(&add_receipt));
        body.append(&receipts);
        let refused_pick = gtk::Label::builder().label(words::receipts_refused()).wrap(true).xalign(0.0).visible(false).build();
        refused_pick.add_css_class("error");
        body.append(&refused_pick);
        refusal("foot", &body, &mut refusals);

        // the foot: the history, the delete
        if !s.is_new && let Some(entry) = self.entry.clone() {
            let foot = adw::PreferencesGroup::new();
            let history = adw::ActionRow::builder().title(words::history_show()).activatable(true).build();
            history.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
            let w = Rc::downgrade(self);
            history.connect_activated(move |_| if let Some(i) = w.upgrade() {
                i.nav.push(&crate::entry_history::page(i.pot, &entry, &i.toasts));
            });
            foot.add(&history);
            if s.editable {
                let delete = adw::ButtonRow::builder().title(words::entry_delete()).build();
                delete.add_css_class("destructive-action");
                let w = Rc::downgrade(self);
                delete.connect_activated(move |_| if let Some(i) = w.upgrade() {
                    i.ask_delete();
                });
                foot.add(&delete);
            }
            body.append(&foot);
        }
        self.holder.set_child(Some(&body));
        if rest.is_none() && self.split.borrow().as_ref().is_some_and(|p| p.item.is_none()) {
            self.nav.pop_to_page(&self.form_page);
        }
        Form {
            shape,
            kind,
            title,
            title_row,
            date,
            calendar,
            category,
            currency,
            rate,
            amount,
            from,
            to,
            payers,
            add_payer,
            total,
            items,
            rest,
            preview,
            preview_rows: RefCell::new(Vec::new()),
            invalid,
            refusals,
            receipts,
            receipt_rows: RefCell::new(Vec::new()),
            receipts_drawn: RefCell::new(String::new()),
            add_receipt,
            refused_pick,
        }
    }

    /// The receipts' rows, built anew only when what they show changed (never on a keystroke).
    fn draw_receipts(self: &Rc<Self>, f: &Form, s: &EditorShown) {
        f.add_receipt.set_visible(s.editable && s.can_add_receipt);
        f.refused_pick.set_visible(s.refused_pick);
        let drawn = format!("{}|{:?}", s.editable, s.receipts.iter().map(|r| (&r.id, &r.name, r.held, r.opens)).collect::<Vec<_>>());
        if *f.receipts_drawn.borrow() == drawn {
            return;
        }
        *f.receipts_drawn.borrow_mut() = drawn;
        for r in f.receipt_rows.borrow_mut().drain(..) {
            f.receipts.remove(&r);
        }
        if s.receipts.is_empty() {
            let none = adw::ActionRow::builder().title(words::receipts_none()).build();
            none.add_css_class("dim-label");
            f.receipts.add(&none);
            f.receipt_rows.borrow_mut().push(none.upcast());
        }
        for r in &s.receipts {
            let row = adw::ActionRow::builder().title(glib_escape(&r.name)).build();
            if r.held {
                row.set_subtitle(&words::receipts_held());
            }
            if r.opens {
                row.set_activatable(true);
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                let (w, id, name) = (Rc::downgrade(self), r.id.clone(), r.name.clone());
                row.connect_activated(move |_| if let Some(i) = w.upgrade() {
                    eprintln!("veyq: receipt viewer open");
                    crate::receipt_viewer::open(&i.dialog, i.pot, &id, &name);
                });
            }
            if s.editable {
                let remove = gtk::Button::builder().icon_name("list-remove-symbolic").tooltip_text(words::receipts_remove()).valign(gtk::Align::Center).build();
                remove.add_css_class("flat");
                let (w, id) = (Rc::downgrade(self), r.id.clone());
                remove.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    bridge::send(Call::EditorDropReceipt { editor: i.editor, receipt: id.clone() });
                });
                row.add_prefix(&remove);
            }
            f.receipts.add(&row);
            f.receipt_rows.borrow_mut().push(row.upcast());
        }
    }

    /// "Add receipt": GTK's open dialog for images and PDFs, several at once; the core reads what was picked.
    fn pick_receipts(self: &Rc<Self>) {
        let parent = self.dialog.root().and_downcast::<gtk::Window>();
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let receipts = gtk::FileFilter::new();
            receipts.add_mime_type("image/*");
            receipts.add_mime_type("application/pdf");
            receipts.set_name(Some(&words::receipts_title()));
            let filters = gtk::gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&receipts);
            let dialog = gtk::FileDialog::builder().title(words::receipts_add()).filters(&filters).default_filter(&receipts).modal(true).build();
            let files = match dialog.open_multiple_future(parent.as_ref()).await {
                Ok(files) => files,
                Err(e) => {
                    eprintln!("veyq: no receipt picked ({e})");
                    return;
                }
            };
            // the core opens each path itself; one that is no UTF-8 does not cross the wire
            let paths: Vec<String> = (0..files.n_items())
                .filter_map(|n| files.item(n).and_downcast::<gtk::gio::File>())
                .filter_map(|f| f.path().and_then(|p| p.to_str().map(str::to_owned)))
                .collect();
            let Some(i) = w.upgrade() else { return };
            let count = paths.len();
            let answered = bridge::call(&Call::EditorAddReceipts { editor: i.editor, paths }).await.map(|(a, _)| a);
            eprintln!("veyq: {count} receipts picked: {answered:?}");
        });
    }

    /// A split's row in the form: what it is now as its subtitle; it opens the split's page.
    fn split_row(self: &Rc<Self>, item: Option<i32>) -> adw::ActionRow {
        let row = adw::ActionRow::builder().title(words::ed_split_title()).activatable(true).build();
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let w = Rc::downgrade(self);
        row.connect_activated(move |_| if let Some(i) = w.upgrade() {
            i.open_split(item);
        });
        row
    }

    fn open_split(self: &Rc<Self>, item: Option<i32>) {
        let view = adw::ToolbarView::new();
        view.add_top_bar(&adw::HeaderBar::new());
        let page = adw::NavigationPage::new(&view, &words::ed_split_title());
        *self.split.borrow_mut() = Some(SplitPage {
            item,
            page: page.clone(),
            view,
            shape: String::new(),
            folded: gtk::Label::new(None),
            modes: None,
            rows: Vec::new(),
            hint: gtk::Label::new(None),
        });
        self.nav.push(&page);
        if let Some(s) = self.last.borrow().clone() {
            self.drawing.set(true);
            self.draw_split(&s);
            self.drawing.set(false);
        }
    }

    /// The split's page, where one is shown: built anew when its shape changed, else updated in place; gone with its split.
    fn draw_split(self: &Rc<Self>, s: &EditorShown) {
        let Some(item) = self.split.borrow().as_ref().map(|p| p.item) else { return };
        let split = match item {
            Some(i) => s.items.get(i as usize).map(|it| &it.split),
            None => s.rest.as_ref(),
        };
        let Some(split) = split else {
            self.nav.pop_to_page(&self.form_page);
            return;
        };
        let shape = format!("{}|{}|{:?}", split.expanded, s.editable, split.people.iter().map(|p| &p.person).collect::<Vec<_>>());
        let rebuild = self.split.borrow().as_ref().is_some_and(|p| p.shape != shape);
        if rebuild {
            self.build_split(item, split, s.editable, shape);
        }
        if let Some(p) = self.split.borrow().as_ref() {
            update_split(p, split);
        }
    }

    fn build_split(self: &Rc<Self>, item: Option<i32>, split: &EditorSplit, editable: bool, shape: String) {
        let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
        body.set_margin_top(12);
        body.set_margin_bottom(24);
        body.set_margin_start(12);
        body.set_margin_end(12);
        let folded = gtk::Label::builder().wrap(true).xalign(0.0).build();
        let hint = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        hint.add_css_class("dim-label");
        let mut modes = None;
        let mut rows = Vec::new();
        if !split.expanded {
            body.append(&folded);
            if editable {
                let adjust = gtk::Button::builder().label(words::ed_adjust()).halign(gtk::Align::Start).build();
                adjust.add_css_class("pill");
                let w = Rc::downgrade(self);
                adjust.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    i.edit(EditorChange::EditExpand { item });
                });
                body.append(&adjust);
            }
        } else {
            let group = adw::ToggleGroup::builder().halign(gtk::Align::Center).build();
            for (name, label) in [("SHARES", words::ed_by_shares()), ("PERCENT", words::ed_by_percent()), ("AMOUNTS", words::ed_by_amount())] {
                group.add(adw::Toggle::builder().label(label).name(name).build());
            }
            group.set_sensitive(editable);
            let w = Rc::downgrade(self);
            group.connect_active_name_notify(move |g| {
                let Some(i) = w.upgrade() else { return };
                if i.drawing.get() {
                    return;
                }
                let mode = match g.active_name().as_deref() {
                    Some("PERCENT") => SplitMode::Percent,
                    Some("AMOUNTS") => SplitMode::Amounts,
                    _ => SplitMode::Shares,
                };
                i.edit(EditorChange::EditSplitMode { item, mode });
            });
            body.append(&group);
            modes = Some(group);
            let people = adw::PreferencesGroup::new();
            people.set_sensitive(editable);
            for p in &split.people {
                let row = adw::ActionRow::builder().title(glib_escape(&p.name)).build();
                let check = gtk::CheckButton::builder().valign(gtk::Align::Center).build();
                let (w, person) = (Rc::downgrade(self), p.person.clone());
                check.connect_toggled(move |c| {
                    let Some(i) = w.upgrade() else { return };
                    if !i.drawing.get() {
                        i.edit(EditorChange::EditToggle { item, person: person.clone(), on: c.is_active() });
                    }
                });
                row.add_prefix(&check);
                row.set_activatable_widget(Some(&check));
                let entry = gtk::Entry::builder().width_chars(8).valign(gtk::Align::Center).input_purpose(gtk::InputPurpose::Number).build();
                gtk::prelude::EditableExt::set_alignment(&entry, 1.0);
                let person = p.person.clone();
                let value = Field::new(self, &entry, move |text| EditorChange::EditShare { item, person: person.clone(), text });
                let percent = gtk::Label::new(Some("%"));
                percent.add_css_class("dim-label");
                row.add_suffix(&entry);
                row.add_suffix(&percent);
                people.add(&row);
                rows.push((p.person.clone(), check, value, percent));
            }
            body.append(&people);
            body.append(&hint);
            if editable {
                let equally = gtk::Button::builder().label(words::ed_everyone_equally()).halign(gtk::Align::Start).build();
                equally.add_css_class("flat");
                let w = Rc::downgrade(self);
                equally.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    i.edit(EditorChange::EditEqually { item });
                });
                body.append(&equally);
            }
        }
        let clamp = adw::Clamp::builder().maximum_size(560).child(&body).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        if let Some(p) = self.split.borrow_mut().as_mut() {
            p.view.set_content(Some(&scroll));
            p.shape = shape;
            p.folded = folded;
            p.modes = modes;
            p.rows = rows;
            p.hint = hint;
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Editor { shown: Some(s), .. }) => inner.draw(&s),
            Ok(Update::Editor { shown: None, .. }) => {
                inner.dialog.close();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: an editor update did not read: {:?}", e.0),
        }
    }
}

/// What makes the form's widgets: a change here builds it anew (never one a keystroke makes).
fn shape_of(s: &EditorShown) -> String {
    format!(
        "{:?}|{}|{}|{:?}|{:?}|{}|{}|{}|{}|{}|{}",
        s.kind,
        s.editable,
        s.is_new,
        s.people.iter().map(|p| (&p.id, &p.name)).collect::<Vec<_>>(),
        s.categories.iter().map(|c| &c.id).collect::<Vec<_>>(),
        s.currencies.len(),
        s.currency != s.base,
        s.payers.len(),
        s.items.len(),
        s.rest.is_some(),
        s.rest_is_whole,
    ) + &if s.currency != s.base { s.currency.clone() } else { String::new() }
}

/// Everything [s] says, set on the form's widgets (the drawing flag held).
fn update(f: &Form, s: &EditorShown) {
    f.kind.select(match s.kind {
        EntryKind::Expense => "EXPENSE",
        EntryKind::Refund => "REFUND",
        EntryKind::Transfer => "TRANSFER",
    });
    f.title.set(&s.title);
    f.date.set_subtitle(&said::day(&s.date));
    let mut d = s.date.splitn(3, '-').map(str::parse::<i32>);
    if let (Some(Ok(y)), Some(Ok(m)), Some(Ok(day))) = (d.next(), d.next(), d.next())
        && let Ok(t) = glib::DateTime::from_local(y, m, day, 12, 0, 0.0)
    {
        f.calendar.set_date(&t);
    }
    if let Some(c) = &f.category {
        c.select(&s.category);
    }
    f.currency.select(&s.currency);
    f.currency.row.set_subtitle(&if s.currency == s.base {
        String::new()
    } else {
        match &s.rate_fill {
            Some(r) if r.state == RateState::Filling => words::ed_rate_loading(),
            Some(r) if r.state == RateState::Filled => words::ed_rate_filled(&said::day(r.day.as_deref().unwrap_or_default())),
            Some(_) => words::ed_rate_missing(&s.currency),
            None => words::ed_rate_hint(),
        }
    });
    if let Some((rate, _)) = &f.rate {
        rate.set(&s.rate);
    }
    if let Some((amount, _)) = &f.amount {
        amount.set(&s.amount);
    }
    if let Some(c) = &f.from {
        c.select(&s.from);
    }
    if let Some(c) = &f.to {
        c.select(&s.to);
    }
    for ((person, paid, remove), p) in f.payers.iter().zip(&s.payers) {
        person.select(&p.person);
        paid.set(&p.amount);
        remove.set_visible(s.can_remove_payer && s.editable);
    }
    if let Some(add) = &f.add_payer {
        add.set_visible(s.can_add_payer && s.editable);
    }
    if let Some(total) = &f.total {
        total.set_label(&said::piece(&s.total));
    }
    for ((label, amount, row), item) in f.items.iter().zip(&s.items) {
        label.set(&item.label);
        amount.set(&item.amount);
        row.set_subtitle(&glib_escape(&split_words(&item.split)));
    }
    if let (Some(row), Some(split)) = (&f.rest, &s.rest) {
        row.set_subtitle(&glib_escape(&split_words(split)));
    }
    for r in f.preview_rows.borrow_mut().drain(..) {
        f.preview.remove(&r);
    }
    f.preview.set_visible(!s.preview.is_empty());
    let given = if s.kind == EntryKind::Refund { words::ed_received_column() } else { words::ed_paid_column() };
    for p in &s.preview {
        let row = adw::ActionRow::builder().title(glib_escape(&p.name)).build();
        if let Some(g) = &p.given {
            row.set_subtitle(&glib_escape(&format!("{given} {}", said::piece(g))));
        }
        let share = gtk::Label::new(Some(&said::piece(&p.share)));
        share.add_css_class("numeric");
        row.add_suffix(&share);
        f.preview.add(&row);
        f.preview_rows.borrow_mut().push(row.upcast());
    }
    f.invalid.set_visible(s.invalid);
    // the refusal under its field, the field's row in its error style
    let at = s.refusal.as_ref().map(|r| match (r.at.as_deref(), r.item) {
        (Some("title" | "date" | "rate"), _) => "main".to_owned(),
        (Some("amount" | "transfer"), _) => "transfer".to_owned(),
        (Some("item"), Some(i)) => format!("item{i}"),
        (Some(other), _) if ["payers", "items", "rest"].contains(&other) => other.to_owned(),
        _ => "foot".to_owned(),
    });
    for (key, label) in &f.refusals {
        let here = at.as_deref() == Some(key.as_str());
        label.set_visible(here);
        if here && let Some(r) = &s.refusal {
            label.set_label(&said::notice(&r.words));
        }
    }
    let field = s.refusal.as_ref().and_then(|r| r.at.clone());
    let mark = |w: &gtk::Widget, on: bool| if on { w.add_css_class("error") } else { w.remove_css_class("error") };
    mark(f.title_row.upcast_ref(), field.as_deref() == Some("title"));
    mark(f.date.upcast_ref(), field.as_deref() == Some("date"));
    if let Some((_, row)) = &f.rate {
        mark(row.upcast_ref(), field.as_deref() == Some("rate"));
    }
    if let Some((_, row)) = &f.amount {
        mark(row.upcast_ref(), field.as_deref() == Some("amount"));
    }
}

/// A split's line in the form: folded, its one line; else its mode.
fn split_words(split: &EditorSplit) -> String {
    if !split.expanded {
        return said::notice(&split.folded);
    }
    match split.mode {
        SplitMode::Shares => words::ed_by_shares(),
        SplitMode::Percent => words::ed_by_percent(),
        SplitMode::Amounts => words::ed_by_amount(),
    }
}

fn update_split(p: &SplitPage, split: &EditorSplit) {
    p.folded.set_label(&said::notice(&split.folded));
    if let Some(m) = &p.modes {
        let name = match split.mode {
            SplitMode::Shares => "SHARES",
            SplitMode::Percent => "PERCENT",
            SplitMode::Amounts => "AMOUNTS",
        };
        if p.modes.as_ref().and_then(|g| g.active_name()).as_deref() != Some(name) {
            m.set_active_name(Some(name));
        }
    }
    for ((person, check, value, percent), shown) in p.rows.iter().zip(&split.people) {
        debug_assert_eq!(person, &shown.person);
        if check.is_active() != shown.on {
            check.set_active(shown.on);
        }
        value.set(&shown.value);
        value.widget.set_visible(shown.on);
        percent.set_visible(shown.on && split.mode == SplitMode::Percent);
    }
    p.hint.set_visible(split.hint.is_some());
    if let Some(h) = &split.hint {
        p.hint.set_label(&said::notice(h));
    }
}
