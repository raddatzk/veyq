import SwiftUI
import VeyqCore

/// A Memory's or a chapter's page (Memories' `MemoryDetail.vue`, its gallery): the chapters a Memory holds, then every
/// medium as a square tile of its thumbnail in the grid's order (newest first by capture time), a video marked as one; a
/// tile opens the lightbox, which pages through the gallery showing each medium's preview with its name and capture time.
struct MemoryScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var gallery: MemoryMedia.Gallery?
    @State private var failed = false
    @State private var chapters: [NodeRef] = []
    @State private var open: LightboxStart?

    private var items: [MemoryMedia.Item] { gallery?.items ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                NodeHeroRow()
                if !chapters.isEmpty {
                    Text("Chapters").font(.headline).padding(.horizontal)
                    ForEach(chapters, id: \.nodeId) { c in
                        Button {
                            if let o = c.asObject() { model.open(o) }
                        } label: {
                            Label(c.name, systemImage: Names.kindSymbol(c.kind)).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal)
                    }
                }
                if failed {
                    Text("The gallery could not be loaded.").foregroundStyle(Role.danger).padding()
                } else if gallery == nil {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if items.isEmpty {
                    Text("No photos or videos yet.").foregroundStyle(Role.muted).padding()
                } else {
                    Text(items.count == 1 ? "1 item" : "\(items.count) items").font(.footnote).foregroundStyle(Role.muted).padding(.horizontal)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 2)], spacing: 2) {
                        ForEach(Array(items.enumerated()), id: \.element.mediaId) { index, item in
                            Button { open = LightboxStart(index: index) } label: { MediaTile(model: model, item: item) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle(ref.name)
        // read again whenever the lists are
        .task(id: model.rowDetails.count) {
            if let read = await model.memoryGallery(scopeId: ref.scopeId, nodeId: ref.nodeId) { gallery = read; failed = false } else { failed = true }
            chapters = ref.kind == "MEMORY"
                ? await model.scopeNodes(ref.scopeId).filter { $0.parentId == ref.nodeId && $0.kind == "CHAPTER" }
                : []
        }
        #if os(iOS)
        .fullScreenCover(item: $open) { start in Lightbox(model: model, items: items, index: start.index) }
        #else
        .sheet(item: $open) { start in Lightbox(model: model, items: items, index: start.index).frame(minWidth: 640, minHeight: 480) }
        #endif
    }
}

/// Where the lightbox opens (a sheet's item).
private struct LightboxStart: Identifiable {
    let index: Int
    var id: Int { index }
}

/// One square tile: the medium's grid tier cropped to fill, a video marked; an icon where it has no tile to show.
private struct MediaTile: View {
    let model: AppModel
    let item: MemoryMedia.Item
    @State private var picture: CGImage?

    var body: some View {
        Rectangle().fill(Role.line)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let picture {
                    Image(decorative: picture, scale: 1).resizable().scaledToFill()
                } else {
                    Image(systemName: "photo").foregroundStyle(Role.muted)
                }
            }
            .overlay {
                if item.isVideo { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white) }
            }
            .clipped()
            .accessibilityLabel(item.name ?? "")
            .task(id: item.mediaId) {
                if let blob = item.gridBlob { picture = await model.mediaPicture(item, blob: blob) }
            }
    }
}

/// The lightbox: the gallery paged through, each medium's preview fitted on black with its name and capture time.
private struct Lightbox: View {
    let model: AppModel
    let items: [MemoryMedia.Item]
    @State var index: Int
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        // a bar, the pages, the caption — stacked, not floated over the picture
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white) }
                    .accessibilityLabel("Close")
                Spacer()
            }
            .padding()
            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.mediaId) { i, item in
                    LightboxPage(model: model, item: item).tag(i)
                }
            }
            #if os(iOS)
            .tabViewStyle(.page(indexDisplayMode: .never))
            #endif
            VStack(alignment: .leading) {
                if items.indices.contains(index) {
                    let item = items[index]
                    Text(item.name ?? "").font(.headline)
                    if let at = item.meta?.effectiveCapturedAt {
                        Text(Date(timeIntervalSince1970: Double(truncating: at) / 1000), format: .dateTime.day().month().year().hour().minute())
                            .font(.footnote).opacity(0.8)
                    }
                }
            }
            .foregroundStyle(.white)
            .padding()
        }
        .background(Color.black.ignoresSafeArea())
    }
}

/// One medium in the lightbox: its preview tier, fitted.
private struct LightboxPage: View {
    let model: AppModel
    let item: MemoryMedia.Item
    @State private var picture: CGImage?

    var body: some View {
        ZStack {
            if let picture {
                Image(decorative: picture, scale: 1).resizable().scaledToFit()
            } else {
                ProgressView().tint(.white)
            }
            if item.isVideo { Image(systemName: "play.circle.fill").font(.system(size: 56)).foregroundStyle(.white) }
        }
        .task(id: item.mediaId) { picture = await model.mediaPicture(item, blob: item.lightboxBlob) }
    }
}
