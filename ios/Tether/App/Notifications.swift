import Foundation
import Intents
import TetherCore
import UIKit
import UserNotifications

/// Local notifications for new SMS: OTP codes get a Copy button, every message gets an inline Reply.
final class Notifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifications()
    private let center = UNUserNotificationCenter.current()

    static let smsCategory = "SMS"
    static let otpCategory = "SMS_OTP"
    static let copyAction = "COPY_CODE"
    static let replyAction = "REPLY"
    /// True in the build variant signed with com.apple.developer.usernotifications.communication
    /// (tools/reinstall-ios.sh tries that first): iOS then shows the sender's picture large on the left with the
    /// app's icon as a small badge, like Messages. Without it iOS always puts the app icon on the left.
    static let communicationEntitled: Bool = {
        let v = Bundle.main.object(forInfoDictionaryKey: "TetherCommunicationNotifications")
        return (v as? Bool) ?? ((v as? String)?.uppercased() == "YES")
    }()

    func setUp() {
        center.delegate = self
        let reply = UNTextInputNotificationAction(identifier: Self.replyAction, title: String(localized: "Reply"),
                                                  options: [], textInputButtonTitle: String(localized: "Send"),
                                                  textInputPlaceholder: String(localized: "Message"))
        let copy = UNNotificationAction(identifier: Self.copyAction, title: String(localized: "Copy code"), options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.smsCategory, actions: [reply], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.otpCategory, actions: [copy, reply], intentIdentifiers: []),
        ])
        // Simulator screenshots (-TetherDemoQuiet) must not be covered by the permission alert.
        if ProcessInfo.processInfo.arguments.contains("-TetherDemoQuiet") || AppModel.runningTests { return }
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// Shown as a communication notification, the way Messages does it: the sender's photo (or monogram)
    /// on the left, their name as the title, grouped per conversation.
    func showSms(_ m: Sms, from name: String, photo: String? = nil, sim: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = name
        if let sim { content.subtitle = sim }   // which SIM it came in on (two SIMs only)
        content.sound = .default
        content.threadIdentifier = "sms-\(m.thread)"
        content.userInfo = ["thread": m.thread, "addr": m.addr, "sub": m.sub ?? -1]
        if let code = OTPExtractor.extract(m.body) {
            content.categoryIdentifier = Self.otpCategory
            content.userInfo["code"] = code
            content.interruptionLevel = .timeSensitive
        } else {
            content.categoryIdentifier = Self.smsCategory
        }
        let plain = MessageText.plain(m.body)
        content.body = plain

        let image = INImage(imageData: AvatarImage.png(name: name, photo: photo))
        let sender = INPerson(personHandle: INPersonHandle(value: m.addr, type: .phoneNumber), nameComponents: nil,
                              displayName: name, image: image, contactIdentifier: nil, customIdentifier: m.addr)
        let intent = INSendMessageIntent(recipients: nil, outgoingMessageType: .outgoingMessageText, content: plain,
                                         speakableGroupName: nil, conversationIdentifier: "sms-\(m.thread)",
                                         serviceName: nil, sender: sender, attachments: nil)
        intent.setImage(image, forParameterNamed: \.sender)
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        interaction.donate(completion: nil)
        // Entitled build: a communication notification (sender's picture on the left, app icon as badge).
        // Otherwise iOS keeps the app icon on the left; then a real contact photo, clean, is shown as the
        // thumbnail on the right, and nothing when the sender has no photo.
        var final: UNNotificationContent = content
        if Self.communicationEntitled, let c = try? content.updating(from: intent) {
            final = c
        } else if let photo, let attachment = Self.photoAttachment(photo, key: "sms-\(m.id)") {
            content.attachments = [attachment]
        }
        center.add(UNNotificationRequest(identifier: "sms-\(m.id)", content: final, trigger: nil))
    }

    /// The sender's own photo as a notification thumbnail file (iOS moves the file into its store).
    static func photoAttachment(_ photo: String, key: String) -> UNNotificationAttachment? {
        guard let data = Data(base64Encoded: photo), UIImage(data: data) != nil else { return nil }
        let png = AvatarImage.png(name: "", photo: photo, side: 180)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("notif", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(key)-\(UUID().uuidString).png")
        guard (try? png.write(to: url)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "photo", url: url,
                                             options: [UNNotificationAttachmentOptionsTypeHintKey: "public.png"])
    }

    /// "Missed Call" from a name or number, with the SIM (two SIMs only), as Phone shows it.
    func showMissedCall(_ c: CallEntry, from name: String, sim: String?) {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Missed Call")
        content.body = [name, sim].compactMap { $0 }.joined(separator: " · ")
        content.sound = .default
        content.threadIdentifier = "missed-calls"
        center.add(UNNotificationRequest(identifier: "missed-\(c.id)", content: content, trigger: nil))
    }

    /// Removes delivered notifications of a conversation (it was read on another device).
    func clearSms(thread: Int64) {
        center.getDeliveredNotifications { [center] list in
            let ids = list.filter { $0.request.content.threadIdentifier == "sms-\(thread)" }.map { $0.request.identifier }
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }

    /// Removes delivered notifications of deleted messages.
    func clearSms(ids: [Int64]) {
        center.removeDeliveredNotifications(withIdentifiers: ids.map { "sms-\($0)" })
    }

    func updateBadge(_ n: Int) {
        center.setBadgeCount(n)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        switch response.actionIdentifier {
        case Self.copyAction:
            UIPasteboard.general.string = info["code"] as? String
        case Self.replyAction:
            if let text = (response as? UNTextInputNotificationResponse)?.userText, let addr = info["addr"] as? String {
                let sub = (info["sub"] as? Int).flatMap { $0 >= 0 ? $0 : nil }
                Task { @MainActor in
                    AppModel.shared.sendSms(to: addr, body: text, sub: sub)
                    // Replying means it was read (as in Messages).
                    if let thread = info["thread"] as? Int64 { AppModel.shared.markRead(thread: thread) }
                    // On disk before iOS may suspend us: the outbox survives even if the app is then killed.
                    AppModel.shared.persistNow()
                    completionHandler()
                }
                return
            }
        case UNNotificationDefaultActionIdentifier:
            if let thread = info["thread"] as? Int64 {
                Task { @MainActor in Router.shared.open(thread: thread) }
            }
        default:
            break
        }
        completionHandler()
    }
}

/// Contact photo or an iOS-style grey monogram, as PNG data for notifications.
enum AvatarImage {
    static func initials(_ name: String) -> String? {
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first }.filter { $0.isLetter }
        return letters.isEmpty ? nil : String(letters).uppercased()
    }

    static func png(name: String, photo: String?, side: CGFloat = 120) -> Data {
        let size = CGSize(width: side, height: side)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            let rect = CGRect(origin: .zero, size: size)
            UIBezierPath(ovalIn: rect).addClip()
            if let photo, let data = Data(base64Encoded: photo), let img = UIImage(data: data) {
                let scale = max(side / img.size.width, side / img.size.height)
                let w = img.size.width * scale, h = img.size.height * scale
                img.draw(in: CGRect(x: (side - w) / 2, y: (side - h) / 2, width: w, height: h))
                return
            }
            let colors = [UIColor(red: 0.65, green: 0.67, blue: 0.72, alpha: 1).cgColor,   // theme: ok, Apple's contact-avatar grey
                          UIColor(red: 0.52, green: 0.54, blue: 0.58, alpha: 1).cgColor] as CFArray   // theme: ok, Apple's contact-avatar grey
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: side), options: [])
            }
            if let text = initials(name) {
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: side * 0.42, weight: .medium), .foregroundColor: UIColor.white]
                let t = text as NSString
                let ts = t.size(withAttributes: attrs)
                t.draw(at: CGPoint(x: (side - ts.width) / 2, y: (side - ts.height) / 2), withAttributes: attrs)
            } else if let symbol = UIImage(systemName: "person.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal) {
                let d = side * 0.56
                symbol.draw(in: CGRect(x: (side - d) / 2, y: (side - d) / 2, width: d, height: d))
            }
        }
        return image.pngData() ?? Data()
    }
}
