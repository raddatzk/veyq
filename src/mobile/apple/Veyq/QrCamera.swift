import AVFoundation
import ImageIO
import SwiftUI
import VeyqCore

/// A QR scan's camera (the QR CR, D4): AVFoundation's capture session feeding core's `QrScan` — the Y plane of each
/// `420YpCbCr8BiPlanarFullRange` frame, 720p asked for and fitted to the scan's edge by core (`AppleQr.frame`), copied
/// only when the scan would take it (`ready`). The decoding and what a code means are core's; this only says what the
/// camera did: none on the device (`.none`), refused to the app (`.denied`), or running. Each decode's time goes to the
/// unified log (`veyq-scan:`), never what a code says.
///
/// A debug build reads `VEYQ_DEV_CAMERA=<image>` as its test camera: the image, drawn grey, is the frame offered ten
/// times a second — the path from a frame to a found code where no camera is (the iOS Simulator has none).
final class QrCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let scan: QrScan<AnyObject>
    private let queue = DispatchQueue(label: "veyq.qr-camera")
    private var testFrames: DispatchSourceTimer?
    /// The test camera's picture, for the preview in place of the session's.
    private(set) var testPicture: CGImage?

    init(scan: QrScan<AnyObject>) {
        self.scan = scan
    }

    /// The scan core makes for a caller: [accepts] maps a code's text to what the caller's model made of it (nil: not
    /// ours, skipped); each decode's time is logged.
    static func scan(accepts: @escaping @Sendable (String) -> AnyObject?) -> QrScan<AnyObject> {
        AppleQr.shared.scan(accepts: { accepts($0) }, timing: timing)
    }

    // called on core's threads: @Sendable, never main-actor isolated (src/mobile/CLAUDE.md § Traps)
    private static let timing: @Sendable (KotlinInt, KotlinInt, KotlinDouble, KotlinBoolean) -> Void = { w, h, ms, read in
        NSLog("veyq-scan: decoded %dx%d in %.1f ms, %@ read", w.intValue, h.intValue, ms.doubleValue, read.boolValue ? "a code" : "nothing")
    }

    /// Opens the camera: says `.none` where the device has none, `.denied` where the app may not use it (asking once).
    func start() {
        #if DEBUG
        if let path = ProcessInfo.processInfo.environment["VEYQ_DEV_CAMERA"] {
            startTestCamera(path)
            return
        }
        #endif
        guard let device = Self.device() else {
            scan.camera(camera: .none)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { self.run(device) }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted { self.queue.async { self.run(device) } } else { self.scan.camera(camera: .denied) }
            }
        default:
            scan.camera(camera: .denied)
        }
    }

    func stop() {
        queue.async {
            self.testFrames?.cancel()
            self.testFrames = nil
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    /// The back camera on a phone, the built-in or attached one on a Mac.
    private static func device() -> AVCaptureDevice? {
        #if os(iOS)
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) ?? AVCaptureDevice.default(for: .video)
        #else
        AVCaptureDevice.default(for: .video)
        #endif
    }

    private func run(_ device: AVCaptureDevice) {
        guard let input = try? AVCaptureDeviceInput(device: device) else {
            NSLog("veyq-scan: the camera did not open")
            scan.camera(camera: .none)
            return
        }
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        session.beginConfiguration()
        session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .high
        if session.canAddInput(input) { session.addInput(input) }
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        session.startRunning()
        scan.camera(camera: .running)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard scan.ready, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard CVPixelBufferGetPlaneCount(pixels) >= 1, let base = CVPixelBufferGetBaseAddressOfPlane(pixels, 0) else { return }
        let width = CVPixelBufferGetWidthOfPlane(pixels, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixels, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
        offer(Data(bytesNoCopy: base, count: stride * height, deallocator: .none), width, height, stride)
    }

    private func offer(_ plane: Data, _ width: Int, _ height: Int, _ stride: Int) {
        _ = scan.offer(frame: AppleQr.shared.frame(plane: plane, width: Int32(width), height: Int32(height), rowStride: Int32(stride)))
    }

    #if DEBUG
    private func startTestCamera(_ path: String) {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            NSLog("veyq-scan: the test camera's picture did not open")
            scan.camera(camera: .none)
            return
        }
        testPicture = image
        let width = image.width, height = image.height
        guard let grey = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                   space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue),
              let pixels = grey.data else {
            scan.camera(camera: .none)
            return
        }
        grey.setFillColor(gray: 1, alpha: 1)
        grey.fill(CGRect(x: 0, y: 0, width: width, height: height))
        grey.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let plane = Data(bytes: pixels, count: width * height)
        NSLog("veyq-scan: the test camera shows %@ (%dx%d)", (path as NSString).lastPathComponent, width, height)
        scan.camera(camera: .running)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(100))
        timer.setEventHandler { [weak self] in
            guard let self, self.scan.ready else { return }
            self.offer(plane, width, height, width)
        }
        testFrames = timer
        timer.resume()
    }
    #endif
}

/// The camera's view: its preview while the scan is starting or scanning, and for every other state what the caller
/// draws (`other`) — what a device without a camera does instead, where to allow the camera, what was found (the scan
/// model carries no words of its own). The camera stops once the scan leaves scanning and when the view goes.
struct QrCameraView<Other: View>: View {
    let scan: QrScan<AnyObject>
    @ViewBuilder let other: (any QrScanState) -> Other
    @State private var model: QrCameraModel?

    var body: some View {
        Group {
            if let model, !(model.state is QrScanStateStarting || model.state is QrScanStateScanning) {
                other(model.state)
            } else if let picture = model?.camera.testPicture {
                Image(decorative: picture, scale: 1).resizable().scaledToFit()
            } else if let model {
                CameraPreview(session: model.camera.session)
            } else {
                Color.clear
            }
        }
        .onAppear {
            if model == nil { model = QrCameraModel(scan: scan) }
        }
        .onDisappear {
            model?.stop()
            model = nil
        }
    }
}

/// The view's camera and the scan's state as the view observes it.
@MainActor @Observable
final class QrCameraModel {
    let camera: QrCamera
    private(set) var state: any QrScanState
    private var watch: QrScanWatch?

    init(scan: QrScan<AnyObject>) {
        camera = QrCamera(scan: scan)
        state = scan.current
        watch = scan.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in self?.changed(state) }
        }
        camera.start()
    }

    private func changed(_ new: any QrScanState) {
        state = new
        if !(new is QrScanStateStarting || new is QrScanStateScanning) { camera.stop() }
    }

    func stop() {
        watch?.stop()
        camera.stop()
    }
}

/// The capture session's own preview layer, filling its view.
private struct CameraPreview {
    let session: AVCaptureSession
}

#if os(iOS)
final class CameraLayerView: UIView {
    override static var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

extension CameraPreview: UIViewRepresentable {
    func makeUIView(context: UIViewRepresentableContext<CameraPreview>) -> CameraLayerView {
        let view = CameraLayerView()
        view.preview.session = session
        view.preview.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: CameraLayerView, context: UIViewRepresentableContext<CameraPreview>) {}
}
#else
final class CameraLayerView: NSView {
    let preview = AVCaptureVideoPreviewLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        preview.videoGravity = .resizeAspectFill
        wantsLayer = true
        layer?.addSublayer(preview)
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not from a storyboard") }

    override func layout() {
        super.layout()
        preview.frame = bounds
    }
}

extension CameraPreview: NSViewRepresentable {
    func makeNSView(context: NSViewRepresentableContext<CameraPreview>) -> CameraLayerView {
        let view = CameraLayerView()
        view.preview.session = session
        return view
    }

    func updateNSView(_ view: CameraLayerView, context: NSViewRepresentableContext<CameraPreview>) {}
}
#endif
