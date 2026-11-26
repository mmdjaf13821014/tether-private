// Renders the CallKit template icon (40 pt @3x): the Tether handset in white on transparent.
import AppKit
let px = 120
let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.translateBy(x: 0, y: CGFloat(px)); ctx.scaleBy(x: 1, y: -1)
ctx.scaleBy(x: CGFloat(px) / 100, y: CGFloat(px) / 100)
ctx.translateBy(x: -2, y: -2); ctx.scaleBy(x: 1.04, y: 1.04)
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
ctx.addPath(p); ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)); ctx.fillPath()
let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
