import ImageIO
import PDFKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// What the receipts section asks to be presented, held by the editor: a picker's selection, a viewer.
struct ReceiptsUI {
    var importing = false
    var photos: [PhotosPickerItem] = []
    var viewing: String?
}

/// An entry's receipts in the editor (`EntryReceipts.vue`): the ones the entry keeps, each opened by its bytes or taken
/// off (the save drops it), and those picked meanwhile — a photo or a file —, uploaded only when the entry is saved.
/// Images and PDFs are offered; which are taken, and how many, is core's `EntryEditor`; what a receipt IS, the bytes decide
/// when it is opened. Its presentations live in `ReceiptPresenters`, attached once to the editor's Form: a modifier on a
/// Form's Section is attached to every row of it, so a sheet there is presented by none of them and a change handler runs
/// once per row.
struct EntryReceipts: View {
    @Bindable var model: AppModel
    let view: PotView
    let state: EntryEditor.State
    @Binding var ui: ReceiptsUI

    var body: some View {
        let kept = state.form.receipts
        Section {
            if kept.isEmpty && state.pending.isEmpty { Text(say(.receiptsNone)).foregroundStyle(Role.muted) }
            ForEach(kept, id: \.self) { id in
                let r = view.receipt(receiptId: id)
                HStack {
                    Button(Receipts.shared.downloadName(name: r?.name, type: r?.type)) { ui.viewing = id }
                        .buttonStyle(.borderless)
                        .disabled(r == nil || r?.blocked == true)
                    Spacer()
                    if state.editable {
                        Button { model.dropReceipt(id) } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(say(.receiptsRemove))
                    }
                }
            }
            ForEach(state.pending, id: \.id) { p in
                HStack {
                    Text(p.name).foregroundStyle(Role.muted)
                    Spacer()
                    Button { model.dropReceipt(p.id) } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(say(.receiptsRemove))
                }
            }
            if state.refusedPick { Text(say(.receiptsRefused)).font(.caption).foregroundStyle(Role.danger) }
            if state.canAddReceipt {
                PhotosPicker(selection: $ui.photos, matching: .images) { Label(say(.receiptsAddPhoto), systemImage: "photo") }
                Button { ui.importing = true } label: { Label(say(.receiptsAddFile), systemImage: "doc") }
            }
        } header: { Text(say(.receiptsTitle)) }
    }
}

/// The receipts section's file importer, photo loading and viewer, attached once to the editor's Form.
struct ReceiptPresenters: ViewModifier {
    @Bindable var model: AppModel
    let view: PotView
    @Binding var ui: ReceiptsUI

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $ui.importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                let picked = urls.map { url in
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    // at most one byte past the cap: core refuses a larger file, which is never read whole
                    let data = (try? FileHandle(forReadingFrom: url)).flatMap { h in
                        defer { try? h.close() }
                        return try? h.read(upToCount: Int(Receipts.shared.READ_LIMIT))
                    }
                    let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? ""
                    let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
                    return AppleSessions.shared.picked(data: data, type: type, name: url.lastPathComponent, modifiedAt: modified)
                }
                model.addReceipts(picked)
            }
            .onChange(of: ui.photos) { _, items in
                guard !items.isEmpty else { return }
                ui.photos = []
                Task {
                    var picked: [EntryEditor.Picked] = []
                    for item in items {
                        let data = try? await item.loadTransferable(type: Data.self)
                        let type = item.supportedContentTypes.first?.preferredMIMEType ?? ""
                        let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                        picked.append(AppleSessions.shared.picked(data: data, type: type, name: "photo.\(ext)", modifiedAt: nil))
                    }
                    model.addReceipts(picked)
                }
            }
            .sheet(item: Binding(get: { ui.viewing.map(ViewingId.init) }, set: { ui.viewing = $0?.id })) { v in
                ReceiptViewer(model: model, view: view, receiptId: v.id)
            }
    }
}

private struct ViewingId: Identifiable { let id: String }

/// One receipt opened: an image drawn from its bytes, a PDF through PDFKit from memory, anything else a copy to save.
struct ReceiptViewer: View {
    @Bindable var model: AppModel
    let view: PotView
    let receiptId: String
    @State private var data: Data?
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let r = view.receipt(receiptId: receiptId)
        let name = Receipts.shared.downloadName(name: r?.name, type: r?.type)
        NavigationStack {
            Group {
                if failed {
                    ContentUnavailableView(say(.receiptsUnreadable), systemImage: "exclamationmark.triangle")
                } else if let data {
                    switch AppleSessions.shared.receiptKind(data: data) {
                    case .image:
                        if let image = platformImage(data) { ScrollView { image.resizable().scaledToFit().padding() } }
                        else { downloadOnly }
                    case .pdf: PDFPages(data: data)
                    default: downloadOnly
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(name)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.receiptsClose)) { dismiss() } }
                if let data {
                    ToolbarItem(placement: .primaryAction) {
                        // offered as what its bytes are, so a picture's share sheet has "Save Image" to Photos
                        ShareLink(item: ReceiptFile(data: data, name: name, type: Self.contentType(data)), preview: SharePreview(name)) {
                            Label(say(.receiptsSave), systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
        }
        .task(id: receiptId) {
            if let d = await model.openReceipt(receiptId) { data = d } else { failed = true }
        }
    }

    /// What a receipt's bytes are, read from the bytes alone (ImageIO), never from its sealed type: a peer's statement.
    private static func contentType(_ data: Data) -> UTType {
        if let source = CGImageSourceCreateWithData(data as CFData, nil), let id = CGImageSourceGetType(source) as String?,
           let type = UTType(id), type.conforms(to: .image) {
            return type
        }
        return AppleSessions.shared.receiptKind(data: data) == .pdf ? .pdf : .data
    }

    private var downloadOnly: some View {
        ContentUnavailableView(say(.receiptsCannotShow), systemImage: "doc", description: Text(say(.receiptsSaveToOpen)))
    }

    private func platformImage(_ data: Data) -> Image? {
        #if os(iOS)
        UIImage(data: data).map(Image.init(uiImage:))
        #else
        NSImage(data: data).map(Image.init(nsImage:))
        #endif
    }
}

/// A receipt's bytes handed to the share sheet under its name.
struct ReceiptFile: Transferable {
    let data: Data
    let name: String
    /// What the bytes are; an image's is what lets the share sheet save it to Photos.
    var type: UTType = .data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .data) { $0.data }
            .suggestedFileName { $0.name }
        // the same bytes as the image (or PDF) they are, for the targets that take only that
        DataRepresentation(exportedContentType: .image) { $0.data }
            .suggestedFileName { $0.name }
            .exportingCondition { $0.type.conforms(to: .image) }
        DataRepresentation(exportedContentType: .pdf) { $0.data }
            .suggestedFileName { $0.name }
            .exportingCondition { $0.type == .pdf }
    }
}

