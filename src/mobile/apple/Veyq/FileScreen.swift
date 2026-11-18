import AVKit
import PDFKit
import SwiftUI
import VeyqCore

/// A file's page (Depot's `FileDetail` and `FileViewer`, `docs/apps/depot.md` § The viewer), drawn from core's `FileModel`:
/// its facts and uploader, and the file shown IN THE APP as core decided by its bytes — an image (decoded to core's edge), a
/// PDF (PDFKit from its bytes in memory, its links not followed), a text as plain text, an audio or video played here
/// through core's range reader; a file the bytes do not name, or one too large, is offered as a download. Nothing decrypted
/// is written to show it (ADR 0047).
struct FileScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef

    private var node: NodeAt { ref.at }
    private var state: FileModel.State? { model.fileState(node) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let state {
                if !state.facts.isEmpty {
                    Text(state.facts.map { say(piece: $0) }.joined(separator: " · ")).font(.footnote).foregroundStyle(Role.muted)
                }
                // who uploaded it (Depot's header fact): a member by name, one who left, a guest by the name it gave itself
                if let uploader = state.uploader { Text(say(notice: uploader)).font(.footnote).foregroundStyle(Role.muted) }
            }
            Group {
                switch state?.shown {
                case let s as FileModel.ShownImage: FileImage(shown: s)
                case let s as FileModel.ShownText: FileText(shown: s)
                case let s as FileModel.ShownPdf: FilePdf(shown: s, name: ref.name)
                case let s as FileModel.ShownMedia: FileMedia(shown: s)
                case let s as FileModel.ShownMessage: Text(say(s.words)).foregroundStyle(Role.muted)
                case is FileModel.ShownNotUploaded: Text(say(.depotNotUploaded)).foregroundStyle(Role.muted)
                case is FileModel.ShownFailed: Text(say(.fileOpenFailed)).foregroundStyle(Role.muted)
                default: VStack { ProgressView(); Text(say(.fileOpening)).foregroundStyle(Role.muted) }.frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .padding()
        .navigationTitle(ref.name)
        // the model is the shell's while the page is shown; asking for it here opens it
        .task(id: ref) { _ = model.fileModel(node) }
    }
}

/// A text as plain text, a line a row: one `Text` of a megabyte drew nothing at all (iOS 27, measured 2026-11-12), so the
/// lines are laid out lazily as they scroll into view. The web's two conveniences (`FileViewer.vue`): *Copy* puts exactly
/// what is shown on the pasteboard (a cut text its shown part, which the button and its note say), and *Wrap Lines* wraps
/// long lines instead of scrolling sideways (on at first, as on the web).
private struct FileText: View {
    let shown: FileModel.ShownText
    @State private var lines: [String] = []
    @State private var wrap = true
    @State private var copied: String?

    var body: some View {
        VStack(alignment: .leading) {
            if shown.truncated { Text(say(.fileTextTruncated)).font(.footnote).foregroundStyle(Role.muted) }
            HStack {
                Button(say(shown.truncated ? .fileTextCopyShown : .fileTextCopy), systemImage: "doc.on.doc") {
                    Clipboard.copy(shown.text)
                    copied = say(shown.truncated ? .fileTextCopiedShown : .fileTextCopied)
                }
                Toggle(say(.fileTextWrap), isOn: $wrap).fixedSize()
            }
            if let copied { Text(copied).font(.footnote).foregroundStyle(Role.muted) }
            ScrollView(wrap ? [.vertical] : [.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(lines.indices, id: \.self) { i in
                        let line = Text(verbatim: lines[i].isEmpty ? " " : lines[i])
                            .font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                        if wrap { line.fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading) } else { line.fixedSize() }
                    }
                }
            }
        }
        .task(id: ObjectIdentifier(shown)) { lines = shown.text.components(separatedBy: "\n") }
        .task(id: copied) {
            guard copied != nil else { return }
            try? await Task.sleep(for: .seconds(6))
            copied = nil
        }
    }
}

/// An image decoded to core's edge at most (`FileView.IMAGE_EDGE`), from its bytes in memory.
private struct FileImage: View {
    let shown: FileModel.ShownImage
    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image { Image(decorative: image, scale: 1).resizable().scaledToFit() } else { ProgressView() }
        }
        .task(id: ObjectIdentifier(shown)) {
            guard let data = AppleDepot.shared.bytes(shown: shown) else { return }
            image = CoverImages.image(data as Data, maxPixels: Int(shown.edge))
        }
    }
}

/// A PDF's pages from its bytes in memory (`PDFDocument(data:)`), at most core's bound, the rest said; its text selectable
/// and copied as PDFKit's view offers it (the web's text layer). A password-protected PDF asks for its password in the page
/// first, as the web's does (`FileViewer.vue`): the password goes to PDFKit and nowhere else, held only while the field
/// holds it; cancelled, it says so and the download stays.
private struct FilePdf: View {
    let shown: FileModel.ShownPdf
    let name: String
    @State private var document: PDFDocument?
    @State private var locked = false
    @State private var wrong = false
    @State private var declined = false
    @State private var password = ""
    @State private var cut: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if declined {
                Text(say(.filePdfPasswordDeclined)).foregroundStyle(Role.muted)
            } else if locked, let document {
                Text(say(.filePdfPasswordTitle, name)).font(.headline)
                SecureField(say(.filePdfPasswordLabel), text: $password).textContentType(.password).onSubmit { unlock(document) }
                Text(say(wrong ? .filePdfPasswordWrong : .filePdfPasswordHint)).font(.footnote).foregroundStyle(wrong ? Role.danger : Role.muted)
                HStack {
                    Button(say(.filePdfPasswordOpen)) { unlock(document) }.buttonStyle(.borderedProminent).disabled(password.isEmpty)
                    Button(say(.filePdfPasswordCancel), role: .cancel) { password = ""; declined = true }
                }
            } else if let document {
                PDFPages(document: document)
            } else {
                ProgressView()
            }
            if let cut, !locked, !declined { Text(cut).font(.footnote).foregroundStyle(Role.muted) }
        }
        .task(id: ObjectIdentifier(shown)) {
            guard let bytes = AppleDepot.shared.bytes(shown: shown) as Data?, let doc = PDFDocument(data: bytes) else { return }
            locked = doc.isLocked
            if !locked { opened(doc) }
            document = doc
        }
    }

    /// The typed password tried on the document; the field emptied either way.
    private func unlock(_ doc: PDFDocument) {
        guard !password.isEmpty else { return }
        let typed = password
        password = ""
        if doc.unlock(withPassword: typed) {
            opened(doc)
            wrong = false
            locked = false
        } else {
            wrong = true
        }
    }

    /// The opened document cut to core's bound, the cut said.
    private func opened(_ doc: PDFDocument) {
        cut = FileView.shared.pagesCut(total: Int32(doc.pageCount)).map { say(notice: $0) }
        PDFPages.cut(doc, to: Int(shown.pages))
    }
}

/// An audio or video file played through core's range reader (`ReaderLoader`), with the system's controls; a format the
/// system does not play (a WebM on iOS) says so and offers the download, as a file nothing shows (ADR 0136's Consequences, Depot's screens).
private struct FileMedia: View {
    let shown: FileModel.ShownMedia
    @State private var playback: Playback?
    @State private var unplayable = false

    var body: some View {
        Group {
            if unplayable {
                Text(say(FileView.shared.message(kind: .unsupported))).foregroundStyle(Role.muted)
            } else if let playback {
                VideoPlayer(player: playback.player).frame(minHeight: 240)
            } else {
                ProgressView()
            }
        }
        .task(id: ObjectIdentifier(shown)) {
            let p = Playback(reader: shown.reader, mime: shown.mediaType)
            playback = p
            // the asset asks the reader for its head to tell; nothing is read whole
            if let asset = p.player.currentItem?.asset, (try? await asset.load(.isPlayable)) != true {
                NSLog("veyq-file: \(shown.mediaType) is not playable here")
                unplayable = true
            }
        }
        .onDisappear {
            playback?.player.pause()
            playback = nil
        }
    }
}
