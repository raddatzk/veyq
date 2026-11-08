import PDFKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// What the receipts section asks to be presented, held by the editor: a picker's selection, a viewer, a refusal.
struct ReceiptsUI {
    var importing = false
    var photos: [PhotosPickerItem] = []
    var refused = false
    var viewing: String?
}

/// An entry's receipts in the editor (`EntryReceipts.vue`): the ones the entry keeps, each opened by its bytes or taken
/// off (the save drops it), and those picked meanwhile — a photo or a file —, uploaded only when the entry is saved.
/// Images and PDFs are offered; what a receipt IS, the bytes decide when it is opened. Its presentations live in
/// `ReceiptPresenters`, attached once to the editor's Form: a modifier on a Form's Section is attached to every row of
/// it, so a sheet there is presented by none of them and a change handler runs once per row.
struct EntryReceipts: View {
    let view: PotView
    @Binding var kept: [String]
    @Binding var pending: [PotSession.PendingReceipt]
    @Binding var ui: ReceiptsUI

    var body: some View {
        Section {
            if kept.isEmpty && pending.isEmpty { Text("No receipts.").foregroundStyle(Role.muted) }
            ForEach(kept, id: \.self) { id in
                let r = view.receipt(receiptId: id)
                HStack {
                    Button(Receipts.shared.downloadName(name: r?.name, type: r?.type)) { ui.viewing = id }
                        .buttonStyle(.borderless)
                        .disabled(r == nil || r?.blocked == true)
                    Spacer()
                    Button { kept.removeAll { $0 == id } } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove receipt")
                }
            }
            ForEach(Array(pending.enumerated()), id: \.offset) { _, p in
                HStack {
                    Text(Receipts.shared.downloadName(name: p.name, type: p.type)).foregroundStyle(Role.muted)
                    Spacer()
                    Button { pending.removeAll { $0.id == p.id } } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove receipt")
                }
            }
            if ui.refused { Text("Only images and PDFs up to 10 MB, at most 100 per entry.").font(.caption).foregroundStyle(Role.danger) }
            if kept.count + pending.count < Int(Receipts.shared.MAX_PER_ENTRY) {
                PhotosPicker(selection: $ui.photos, matching: .images) { Label("Add a photo", systemImage: "photo") }
                Button { ui.importing = true } label: { Label("Add a file", systemImage: "doc") }
            }
        } header: { Text("Receipts") }
    }
}

/// The receipts section's file importer, photo loading and viewer, attached once to the editor's Form.
struct ReceiptPresenters: ViewModifier {
    @Bindable var model: AppModel
    let view: PotView
    @Binding var kept: [String]
    @Binding var pending: [PotSession.PendingReceipt]
    @Binding var ui: ReceiptsUI

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $ui.importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                ui.refused = false
                for url in urls {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    guard let data = try? Data(contentsOf: url) else { ui.refused = true; continue }
                    let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? ""
                    let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { Int64($0.timeIntervalSince1970 * 1000) }
                    add(data, type: type, name: url.lastPathComponent, modifiedAt: modified)
                }
            }
            .onChange(of: ui.photos) { _, items in
                guard !items.isEmpty else { return }
                ui.photos = []
                ui.refused = false
                Task {
                    for item in items {
                        guard let data = try? await item.loadTransferable(type: Data.self) else { ui.refused = true; continue }
                        let type = item.supportedContentTypes.first?.preferredMIMEType ?? ""
                        let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                        add(data, type: type, name: "photo.\(ext)", modifiedAt: nil)
                    }
                }
            }
            .sheet(item: Binding(get: { ui.viewing.map(ViewingId.init) }, set: { ui.viewing = $0?.id })) { v in
                ReceiptViewer(model: model, view: view, receiptId: v.id)
            }
    }

    private func add(_ data: Data, type: String, name: String, modifiedAt: Int64?) {
        guard view.receiptAllowed(size: Int64(data.count), type: type, name: name, held: Int32(kept.count + pending.count)) else { ui.refused = true; return }
        let modified = modifiedAt.map { KotlinLong(value: $0) }
        pending.append(AppleSessions.shared.pendingReceipt(id: UUID().uuidString.lowercased(), data: data, type: type, name: name, modifiedAt: modified))
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
                    ContentUnavailableView("This receipt could not be opened.", systemImage: "exclamationmark.triangle")
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
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                if let data {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: ReceiptFile(data: data, name: name), preview: SharePreview(name)) { Label("Save a copy", systemImage: "square.and.arrow.up") }
                    }
                }
            }
        }
        .task(id: receiptId) {
            if let d = await model.openReceipt(receiptId) { data = d } else { failed = true }
        }
    }

    private var downloadOnly: some View {
        ContentUnavailableView("This file cannot be shown here", systemImage: "doc", description: Text("Save a copy to open it."))
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

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .data) { $0.data }
            .suggestedFileName { $0.name }
    }
}

/// A PDF's pages from memory (PDFKit); nothing of the document runs but its rendering.
#if os(iOS)
struct PDFPages: UIViewRepresentable {
    let data: Data
    func makeUIView(context: UIViewRepresentableContext<PDFPages>) -> PDFView {
        let v = PDFView()
        v.autoScales = true
        v.document = PDFDocument(data: data)
        return v
    }
    func updateUIView(_ v: PDFView, context: UIViewRepresentableContext<PDFPages>) {}
}
#else
struct PDFPages: NSViewRepresentable {
    let data: Data
    func makeNSView(context: NSViewRepresentableContext<PDFPages>) -> PDFView {
        let v = PDFView()
        v.autoScales = true
        v.document = PDFDocument(data: data)
        return v
    }
    func updateNSView(_ v: PDFView, context: NSViewRepresentableContext<PDFPages>) {}
}
#endif
