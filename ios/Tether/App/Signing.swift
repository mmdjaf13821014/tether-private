import Foundation

/// When this build's free 7-day signature runs out, read from the provisioning profile inside the app.
enum Signing {
    static let expiry: Date? = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data[start.lowerBound..<end.upperBound], format: nil) as? [String: Any]
        else { return nil }
        return plist["ExpirationDate"] as? Date
    }()

    static var daysLeft: Int? {
        guard let expiry else { return nil }
        return max(0, Int(ceil(expiry.timeIntervalSinceNow / 86_400)))
    }
}
