import SwiftUI

/// The web's colour roles (docs/crosscutting/ui-web-styleguide.md), generated into the asset catalog from
/// `src/web/src/styles/base.css` with their light and dark values (ADR 0123 decision 7). The accent is the brand and
/// the selection — also the app's tint; the green `primary` is creating and confirming only.
enum Role {
    static let bg = Color("veyq-bg")
    static let surface = Color("veyq-surface")
    static let surfaceMuted = Color("veyq-surface-muted")
    static let text = Color("veyq-text")
    static let muted = Color("veyq-muted")
    static let controlText = Color("veyq-control-text")
    static let line = Color("veyq-line")
    static let accent = Color("veyq-accent")
    static let accentSoft = Color("veyq-accent-soft")
    static let primary = Color("veyq-primary")
    static let onFill = Color("veyq-on-fill")
    static let danger = Color("veyq-danger")
    static let hover = Color("veyq-hover")
    static let favorite = Color("veyq-favorite")
}

/// The web's radii: 6 for buttons and fields, 10 (`--app-radius-sm`) for rows and tiles, 12 (`--app-radius`) for cards.
enum Radius {
    static let control: CGFloat = 6
    static let row: CGFloat = 10
    static let card: CGFloat = 12
}

/// The web's section label (`.sidebar-section-label`): small, heavy, uppercase, muted.
struct SectionLabel: View {
    let title: String

    var body: some View {
        Text(title)
            .textCase(.uppercase)
            .font(.system(size: 11.5, weight: .heavy))
            .foregroundStyle(Role.muted)
    }
}

/// The web's leading tile: a 2.1rem square at the row radius, the accent on its soft fill.
struct Tile: View {
    let symbol: String

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Role.accent)
            .frame(width: 34, height: 34)
            .background(Role.accentSoft, in: RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
    }
}

/// The web's brand: the mark and the wordmark beside it (1.35rem at weight 850).
struct Brand: View {
    var body: some View {
        HStack(spacing: 9) {
            Image("VeyqMark").resizable().scaledToFit().frame(width: 30, height: 32)
            Text(verbatim: "veyq").font(.system(size: 21, weight: .heavy)).foregroundStyle(Role.text)
        }
    }
}

/// The web's sidebar chrome: the surface, washed with a trace of the accent from the top (`--app-chrome-pattern`).
struct Chrome: View {
    var body: some View {
        LinearGradient(colors: [Role.accentSoft, Role.surface], startPoint: .top, endPoint: .center)
            .ignoresSafeArea()
    }
}
