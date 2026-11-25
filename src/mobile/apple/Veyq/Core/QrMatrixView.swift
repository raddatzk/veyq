import SwiftUI
import VeyqCore

/// A QR code drawn from core's matrix (`QrEncoder`, the quiet zone included), black on white. Its own file so the TV
/// draws it too (the report's privacy notice, read on another device).
struct QrMatrixView: View {
    let text: String

    var body: some View {
        let matrix: QrMatrix? = QrEncoder.shared.encode(text: text)
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white))
            guard let matrix else { return }
            let side = Int(matrix.side)
            let cell = min(size.width, size.height) / CGFloat(side)
            var path = Path()
            for y in 0..<side {
                for x in 0..<side where matrix.dark(x: Int32(x), y: Int32(y)) {
                    path.addRect(CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell, height: cell))
                }
            }
            ctx.fill(path, with: .color(.black))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}
