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

    /// Circular native Liquid Glass surface for round keypad controls.
    @ViewBuilder
    func tetherLiquidGlassCircle() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: .circle)
        } else {
            self.background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.7))
        }
    }
}
