// Renders the Tether app icon: a green phone handset on a dark tile (drawn from scratch, no SF Symbols).
// The same 100-unit geometry is used by android/app/src/main/res/drawable/ic_launcher_fg.xml.
//   swiftc tools/make-ios-icon.swift -o /tmp/icon && /tmp/icon ios/Tether/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import AppKit

let px = 1024
let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let rgb = CGColorSpace(name: CGColorSpace.sRGB)!
ctx.translateBy(x: 0, y: CGFloat(px)); ctx.scaleBy(x: 1, y: -1) // top-left origin
let bg = CGGradient(colorsSpace: rgb, colors: [CGColor(srgbRed: 0.20, green: 0.20, blue: 0.21, alpha: 1),
                                               CGColor(srgbRed: 0.07, green: 0.07, blue: 0.08, alpha: 1)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(bg, start: .zero, end: CGPoint(x: 0, y: px), options: [])
let k = CGFloat(px) / 100
ctx.scaleBy(x: k, y: k)

/// Handset: earpiece (top-left) and mouthpiece (bottom-right) joined by a handle that bows toward the corner.
func handset() -> CGPath {
    let p = CGMutablePath()
    p.move(to: CGPoint(x: 26, y: 24))
    p.addCurve(to: CGPoint(x: 37, y: 22), control1: CGPoint(x: 29, y: 21), control2: CGPoint(x: 34, y: 20))
    p.addLine(to: CGPoint(x: 43, y: 34))
    p.addCurve(to: CGPoint(x: 41, y: 41), control1: CGPoint(x: 44.5, y: 36.5), control2: CGPoint(x: 43.5, y: 39.5))
    p.addLine(to: CGPoint(x: 37, y: 44))
    p.addCurve(to: CGPoint(x: 56, y: 63), control1: CGPoint(x: 41, y: 52), control2: CGPoint(x: 48, y: 59))
    p.addLine(to: CGPoint(x: 59, y: 59))
    p.addCurve(to: CGPoint(x: 66, y: 57), control1: CGPoint(x: 61.5, y: 56.5), control2: CGPoint(x: 63.5, y: 55.5))
    p.addLine(to: CGPoint(x: 78, y: 63))
    p.addCurve(to: CGPoint(x: 76, y: 74), control1: CGPoint(x: 80, y: 66), control2: CGPoint(x: 79, y: 71))
    p.addLine(to: CGPoint(x: 72, y: 78))
    p.addCurve(to: CGPoint(x: 22, y: 28), control1: CGPoint(x: 50, y: 82), control2: CGPoint(x: 18, y: 50))
    p.closeSubpath()
    return p
}

ctx.addPath(handset())
ctx.clip()
let green = CGGradient(colorsSpace: rgb, colors: [CGColor(srgbRed: 0.36, green: 0.96, blue: 0.40, alpha: 1),
                                                  CGColor(srgbRed: 0.13, green: 0.76, blue: 0.24, alpha: 1)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(green, start: CGPoint(x: 0, y: 20), end: CGPoint(x: 0, y: 80), options: [])
let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
