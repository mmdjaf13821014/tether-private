import AppIntents
import TetherCore

/// "Call with <app>" for Shortcuts and Siri (App Intents need no entitlement). The number may be any text: a
/// Shortcut that receives text or a contact from the Share Sheet passes it here and the first phone number is used.
struct CallNumberIntent: AppIntent {
    static let title: LocalizedStringResource = "Call a Number"
    static let description = IntentDescription("Calls a number through the Android phone.")
    static let openAppWhenRun = true

    @Parameter(title: "Number", requestValueDialog: "Which number?")
    var number: String

    static var parameterSummary: some ParameterSummary { Summary("Call \(\.$number)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let n = DialLink.number(fromLink: number) ?? DialLink.findNumber(in: number) ?? DialLink.dialable(number) else {
            throw $number.needsValueError("That is not a phone number. Which number?")
        }
        // Run by the owner from Shortcuts or Siri: dial at once when linked, otherwise the app asks.
        Router.shared.callNow(n)
        return .result()
    }
}

struct TetherShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: CallNumberIntent(),
                    phrases: ["Call with \(.applicationName)", "Call a number with \(.applicationName)"],
                    shortTitle: "Call a Number", systemImageName: "phone")
    }
}
