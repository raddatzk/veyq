import AVKit
import SwiftUI
import VeyqCore

/// What a file's page shows, once read (core's `FileView`).
enum FileShown {
    case loading
    case failed
    case message(String)
    case picture(CGImage)
    case text(String, truncated: Bool)
    case pdf(URL)
    case media(URL)
}

/// A file's page (Depot's `FileDetail` and `FileViewer`, `docs/apps/depot.md` § The viewer): its facts — size, type,
/// modified — and the file shown IN THE APP by what its first frame's bytes are (core's `FileView`): an image, a PDF (PDFKit,
/// its links not followed), a text as plain text (the first MiB), an audio or video played here; a file the bytes do not
/// name, or one too large, is offered as a download. Nothing is handed to another app: a decrypted PDF or media file lives
/// in the app's caches, named by its SNIFFED type, only while the page is open.
struct FileScreen: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var shown: FileShown = .loading
    @State private var uploader: String?

    private var file: DepotFiles.File? { model.files[ref.nodeId] }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let file, file.hasContent {
                Text(model.fileFacts(file)).font(.footnote).foregroundStyle(Role.muted)
                // who uploaded it (Depot's header fact): a member by name, one who left, a guest by the name it gave itself
                if let uploader { Text(uploader).font(.footnote).foregroundStyle(Role.muted) }
            }
            Group {
                if let file, !file.hasContent {
                    Text("Not uploaded yet").foregroundStyle(Role.muted)
                } else {
                    switch shown {
                    case .loading: VStack { ProgressView(); Text("Opening the file…").foregroundStyle(Role.muted) }.frame(maxWidth: .infinity)
                    case .failed: Text("The file could not be opened. Please try again.").foregroundStyle(Role.muted)
                    case .message(let text): Text(text).foregroundStyle(Role.muted)
                    case .picture(let image): Image(decorative: image, scale: 1).resizable().scaledToFit()
                    case .text(let text, let truncated):
                        VStack(alignment: .leading) {
                            if truncated {
                                Text("Showing only the first megabyte. Download the file to read all of it.").font(.footnote).foregroundStyle(Role.muted)
                            }
                            ScrollView([.vertical, .horizontal]) {
                                Text(text).font(.system(.footnote, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    case .pdf(let url): PDFPages(url: url)
                    case .media(let url): VideoPlayer(player: AVPlayer(url: url)).frame(minHeight: 240)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .padding()
        .navigationTitle(ref.name)
        .task(id: file?.blobId) {
            guard let file, file.hasContent else { return }
            uploader = await model.uploaderText(file)
            shown = await model.fileView(file, nodeName: ref.name)
        }
        .onDisappear { Self.clean(shown) }
        .onChange(of: file?.blobId) { _, _ in Self.clean(shown) }
    }

    /// The decrypted copy goes when the page does.
    private static func clean(_ shown: FileShown) {
        switch shown {
        case .pdf(let url), .media(let url): try? FileManager.default.removeItem(at: url)
        default: break
        }
    }
}
