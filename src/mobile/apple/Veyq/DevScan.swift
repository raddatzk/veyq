import SwiftUI
import VeyqCore

/// Debug builds only — the QR camera's test harness (the QR CR, phase 4), reached without a sign-in: started with
/// `VEYQ_DEV_SCAN=1` in its environment, the app shows `QrCameraView` alone over a scan that accepts any veyq code by its
/// scheme (`veyqpair`, `veyqsignin`, `veyqkit`) and nothing else; with `VEYQ_DEV_CAMERA=<image>` as well, that image is
/// the camera. Its states and each decode's time go to the unified log (`veyq-scan:`); the scheme is all that is shown,
/// never the code's text. A release build compiles none of it.
enum DevScan {
    static var requested: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["VEYQ_DEV_SCAN"] == "1"
        #else
        false
        #endif
    }
}

#if DEBUG
struct DevScanView: View {
    @State private var scan: QrScan<AnyObject> = QrCamera.scan { text in
        ["veyqpair", "veyqsignin", "veyqkit"].first { text.hasPrefix($0 + ":") } as NSString?
    }
    @State private var watch: QrScanWatch?

    var body: some View {
        QrCameraView(scan: scan) { state in
            if let found = state as? QrScanStateFound<AnyObject>, let scheme = found.value as? String {
                VStack(spacing: 12) {
                    Image(systemName: "checkmark.circle").font(.largeTitle)
                    Text(verbatim: scheme).font(.title3)
                }
            } else if state is QrScanStateNoCamera {
                Image(systemName: "video.slash").font(.largeTitle)
            } else if state is QrScanStateDenied {
                Image(systemName: "nosign").font(.largeTitle)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Role.bg)
        .onAppear {
            watch = scan.watch { @Sendable state in
                if let found = state as? QrScanStateFound<AnyObject> {
                    NSLog("veyq-scan: state Found(%@)", (found.value as? String) ?? "?")
                } else {
                    NSLog("veyq-scan: state %@", String(describing: state))
                }
            }
        }
        .onDisappear {
            watch?.stop()
            scan.close()
        }
    }
}
#endif
