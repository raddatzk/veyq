//! A level and a person's Memories drawn as tiles (ADR 0149 decision 8, inbox #950), GNOME's way — Files' grid: a
//! `gtk::FlowBox` of square covers with the name and the row's second line under each, one activation opening what the
//! row opens, the keyboard's arrows moving between them. The grid's frame is the core's (`View.Tiles`, `TileGrid`): how
//! many columns the width holds, each cover's size and the gap between them — asked again whenever the grid's width
//! changes (a window resized, the sidebar shown), so a wider window shows more columns. Every kind gets a tile: a node's
//! cover where it has one (`Shell.RowCover`, as its row's), else its kind's symbol on a card.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call};

/// One tile: its name and second line ([dated]: a Memory's date, told as its row is), its kind's [icon], the node whose
/// cover it shows, and what activating it does.
pub struct Tile {
    pub name: String,
    pub subtitle: Option<String>,
    pub dated: bool,
    pub icon: &'static str,
    pub node_id: String,
    pub open: Box<dyn Fn()>,
}

/// The core's grid at a width: its columns, each cover's size, the gap between tiles.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frame {
    pub columns: u32,
    pub size: f64,
    pub gap: f64,
}

/// The core's answer to `View.Tiles` for two tiles read as a grid's frame: the columns it names, the first cover's size and
/// the gap the second one's place leaves (two tiles always share a row: the grid holds at least two columns).
pub fn frame_of(columns: i32, frames: &[(f64, f64)]) -> Option<Frame> {
    let (&(x0, size), &(x1, _)) = (frames.first()?, frames.get(1)?);
    (columns >= 1 && size > 0.0).then(|| Frame { columns: columns as u32, size, gap: (x1 - x0 - size).max(0.0) })
}

thread_local! {
    /// The frame the core answered last and the width it was for: a level drawn anew starts at it, not at a guess that
    /// jumps a frame later.
    static LAST: Cell<Option<(i32, Frame)>> = const { Cell::new(None) };
}

/// [tiles] as a grid that follows its width; the covers asked for as the rows' are.
pub fn grid(tiles: Vec<Tile>) -> gtk::FlowBox {
    let flow = gtk::FlowBox::builder()
        .homogeneous(true)
        .selection_mode(gtk::SelectionMode::None)
        .activate_on_single_click(true)
        .valign(gtk::Align::Start)
        .build();
    flow.set_widget_name("tiles");
    let covers: Rc<RefCell<Vec<glib::WeakRef<gtk::Widget>>>> = Rc::default();
    let mut opens: Vec<Box<dyn Fn()>> = Vec::new();
    for t in tiles {
        let (child, cover) = tile(&t);
        covers.borrow_mut().push(cover.downgrade());
        opens.push(t.open);
        flow.append(&child);
    }
    flow.connect_child_activated(move |_, child| {
        if let Some(open) = opens.get(child.index() as usize) {
            open();
        }
    });
    if let Some((_, f)) = LAST.with(Cell::get) {
        apply(&flow, &covers, f);
    }
    // the frame asked anew whenever the width changes, checked once a frame (as the gallery's layout is)
    let (laid, asking) = (Rc::new(Cell::new(0)), Rc::new(Cell::new(false)));
    flow.add_tick_callback(move |flow, _| {
        let width = flow.width();
        if width > 0 && width != laid.get() && !asking.get() {
            if let Some((w, f)) = LAST.with(Cell::get).filter(|(w, _)| *w == width) {
                laid.set(w);
                apply(flow, &covers, f);
            } else {
                asking.set(true);
                let (flow, covers, laid, asking) = (flow.downgrade(), covers.clone(), laid.clone(), asking.clone());
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::ViewTiles { width: f64::from(width), count: 2, label_height: 0.0 }).await.map(|(a, _)| a);
                    asking.set(false);
                    let Some(flow) = flow.upgrade() else { return };
                    match answered {
                        Ok(Answer::Tiles { columns, frames }) => {
                            let frames: Vec<(f64, f64)> = frames.iter().map(|f| (f.x, f.size)).collect();
                            let Some(f) = frame_of(columns, &frames) else { return eprintln!("veyq: View.Tiles answered no frame") };
                            if LAST.with(Cell::get).is_none_or(|(_, last)| last.columns != f.columns) {
                                eprintln!("veyq: tiles {width} wide: {} columns of {:.0}", f.columns, f.size);
                            }
                            LAST.with(|l| l.set(Some((width, f))));
                            laid.set(width);
                            apply(&flow, &covers, f);
                        }
                        other => eprintln!("veyq: View.Tiles answered {other:?}"),
                    }
                });
            }
        }
        glib::ControlFlow::Continue
    });
    flow
}

/// The grid set to frame [f]: its columns, the gap across and down, each cover a square of the frame's size.
fn apply(flow: &gtk::FlowBox, covers: &Rc<RefCell<Vec<glib::WeakRef<gtk::Widget>>>>, f: Frame) {
    flow.set_min_children_per_line(f.columns);
    flow.set_max_children_per_line(f.columns);
    let gap = f.gap.round() as u32;
    flow.set_column_spacing(gap);
    flow.set_row_spacing(gap);
    let side = f.size.floor() as i32;
    for cover in covers.borrow().iter().filter_map(glib::WeakRef::upgrade) {
        cover.set_height_request(side);
    }
}

/// One tile: the square cover (the node's, else its kind's symbol on a card), the name, the second line; the screen reader
/// reads the name and the line. Answers the child and its cover, whose height the frame sets.
fn tile(t: &Tile) -> (gtk::FlowBoxChild, gtk::Widget) {
    let icon = gtk::Image::builder().icon_name(t.icon).pixel_size(48).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
    icon.add_css_class("dim-label");
    let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).build();
    let cover = crate::core_ui::home::cover_stack(icon, picture, &t.node_id);
    cover.set_overflow(gtk::Overflow::Hidden);
    cover.add_css_class("card");
    cover.set_height_request(160);
    let name = gtk::Label::builder().label(&t.name).ellipsize(gtk::pango::EllipsizeMode::End).xalign(0.0).width_chars(1).build();
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    column.append(&cover);
    column.append(&name);
    let line = gtk::Label::builder().ellipsize(gtk::pango::EllipsizeMode::End).xalign(0.0).width_chars(1).build();
    line.add_css_class("dim-label");
    line.add_css_class("caption");
    let subtitle = t.subtitle.clone().unwrap_or_default();
    line.set_label(&subtitle);
    column.append(&line);
    let child = gtk::FlowBoxChild::builder().child(&column).tooltip_text(&t.name).build();
    child.update_property(&[gtk::accessible::Property::Label(&t.name)]);
    if !subtitle.is_empty() {
        child.update_property(&[gtk::accessible::Property::Description(&subtitle)]);
    }
    if t.dated {
        crate::core_ui::home::dated_label(&line, &t.node_id);
    }
    (child, cover.upcast())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The grid's frame is the core's answer read whole: its columns, the first cover's size and the gap the second leaves.
    #[test]
    fn the_frame_is_the_cores_answer_read_whole() {
        assert_eq!(frame_of(2, &[(0.0, 174.0), (186.0, 174.0)]), Some(Frame { columns: 2, size: 174.0, gap: 12.0 }));
        assert_eq!(frame_of(5, &[(0.0, 160.4), (172.4, 160.4)]).map(|f| (f.columns, f.gap.round())), Some((5, 12.0)));
        assert_eq!(frame_of(2, &[(0.0, 174.0)]), None, "one frame says no gap");
        assert_eq!(frame_of(0, &[(0.0, 1.0), (2.0, 1.0)]), None, "no columns");
    }
}
