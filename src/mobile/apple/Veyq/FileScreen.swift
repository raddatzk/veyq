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
                case let s as FileModel.ShownText:
                    VStack(alignment: .leading) {
                        if s.truncated { Text(say(.fileTextTruncated)).font(.footnote).foregroundStyle(Role.muted) }
                        ScrollView([.vertical, .horizontal]) {
                            Text(s.text).font(.system(.footnote, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                case let s as FileModel.ShownPdf: FilePdf(shown: s)
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

/// A PDF's pages from its bytes in memory (`PDFDocument(data:)`), at most core's bound, the rest said.
private struct FilePdf: View {
    let shown: FileModel.ShownPdf
    @State private var data: Data?
    @State private var cut: String?

    var body: some View {
        VStack(alignment: .leading) {
            if let data { PDFPages(data: data, maxPages: Int(shown.pages)) } else { ProgressView() }
            if let cut { Text(cut).font(.footnote).foregroundStyle(Role.muted) }
        }
        .task(id: ObjectIdentifier(shown)) {
            guard let bytes = AppleDepot.shared.bytes(shown: shown) as Data? else { return }
            let total = PDFDocument(data: bytes)?.pageCount ?? 0
            cut = FileView.shared.pagesCut(total: Int32(total)).map { say(notice: $0) }
            data = bytes
        }
    }
}

/// An audio or video file played through core's range reader (`ReaderLoader`), with the system's controls.
private struct FileMedia: View {
    let shown: FileModel.ShownMedia
    @State private var playback: Playback?

    var body: some View {
        Group {
            if let playback { VideoPlayer(player: playback.player).frame(minHeight: 240) } else { ProgressView() }
        }
        .task(id: ObjectIdentifier(shown)) {
            let p = Playback(reader: shown.reader, mime: shown.mediaType)
            playback = p
        }
        .onDisappear {
            playback?.player.pause()
            playback = nil
        }
    }
}
