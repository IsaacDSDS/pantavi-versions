import AppKit

// Renders the 320x180dp Leanback banner at xhdpi (640x360 px) using the STYLES.md tokens.
// usage: swift render_banner.swift <out.png> [title] [subtitle]
let out = CommandLine.arguments[1]
let titleText = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "Pantavi"
let subtitleText = CommandLine.arguments.count > 3 ? CommandLine.arguments[3] : "Screen mirroring"
let w = 640.0, h = 360.0
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(w), pixelsHigh: Int(h), bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let ctx = NSGraphicsContext.current!.cgContext

func c(_ hex: UInt32, _ a: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: a)
}
let bg = c(0x0B0B0F), accent = c(0x0A84FF), primary = c(0xF5F5F7), secondary = c(0xA1A1AA)

bg.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
// soft accent glow
let glow = NSGradient(starting: c(0x0A84FF, 0.35), ending: c(0x0A84FF, 0))!
glow.draw(fromCenter: NSPoint(x: 150, y: 180), radius: 0, toCenter: NSPoint(x: 150, y: 180), radius: 230, options: [])

// icon (same geometry as ic_brand.xml, viewport 48 -> scale 3, centered at x=150)
ctx.saveGState()
ctx.translateBy(x: 78, y: 180 + 72)
ctx.scaleBy(x: 3, y: -3)
accent.setStroke(); accent.setFill()
let tv = NSBezierPath(roundedRect: NSRect(x: 5, y: 8, width: 38, height: 26), xRadius: 4, yRadius: 4)
tv.lineWidth = 3; tv.stroke()
let stand = NSBezierPath(); stand.move(to: NSPoint(x: 17, y: 40)); stand.line(to: NSPoint(x: 31, y: 40))
stand.lineWidth = 3; stand.lineCapStyle = .round; stand.stroke()
NSBezierPath(roundedRect: NSRect(x: 12, y: 13, width: 8, height: 16), xRadius: 2, yRadius: 2).fill()
for r in [5.0, 10.0] {
    let arc = NSBezierPath()
    arc.appendArc(withCenter: NSPoint(x: 21, y: 21), radius: r, startAngle: -50, endAngle: 50)
    arc.lineWidth = 2.5; arc.lineCapStyle = .round; arc.stroke()
}
ctx.restoreGState()

let title = NSAttributedString(string: titleText, attributes: [
    .font: NSFont.systemFont(ofSize: 64, weight: .semibold), .foregroundColor: primary])
title.draw(at: NSPoint(x: 268, y: 178))
let sub = NSAttributedString(string: subtitleText, attributes: [
    .font: NSFont.systemFont(ofSize: 30, weight: .regular), .foregroundColor: secondary])
sub.draw(at: NSPoint(x: 270, y: 132))

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
