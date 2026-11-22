import Foundation
import MapKit
import SwiftUI
import VeyqCore

/// How a Memory's date reads (the web's `memoryDateRange`): a day, a range, "Since …", numeric; nil without one.
enum MemoryDateText {
    static func date(_ day: String?) -> Date? { IsoDay.date(day) }
    static func day(_ date: Date) -> String { IsoDay.iso(date) }

    static func text(_ d: MemoryDate) -> String? {
        guard let from = d.from, !from.isEmpty, IsoDay.date(from) != nil else { return nil }
        if d.mode == .ongoing { return say(.memoryDateSince, Shown.shared.memoryDay(iso: from, language: shownLanguage)) }
        return Shown.shared.dayRange(from: from, to: d.to, language: shownLanguage)
    }

    /// A Memories row's second line: its range, or nothing.
    static func row(_ d: MemoryDate) -> String? { text(d) }
}

extension MapMath {
    /// The camera framing `points` in a map of `size` (core's `Viewport.fit`); the world view without a point.
    static func framing(_ points: [LatLng], size: CGSize) -> MapCameraPosition {
        let w = max(size.width, 200), h = max(size.height, 200)
        let v = Viewport.companion.fit(points: points, width: w, height: h, padding: Viewport.companion.FIT_PADDING_PX, maxZoom: Viewport.companion.FIT_MAX_ZOOM)
            ?? Viewport(center: MemoryLocation.companion.WORLD_CENTER, zoom: MemoryLocation.companion.WORLD_ZOOM, width: w, height: h)
        return .region(region(v))
    }
}
