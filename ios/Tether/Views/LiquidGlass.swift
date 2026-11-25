import SwiftUI

extension View {
    /// Uses the native iOS 26 Liquid Glass material, with a compatible fallback on earlier iOS versions.
    @ViewBuilder
    func tetherLiquidGlass(cornerRadius: CGFloat = 16) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            self.background(.ultraThinMaterial,
                            in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}
