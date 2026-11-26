import Foundation
import Testing
@testable import TetherCore

/// One-time codes as real senders write them; the copy button must offer the code and never an amount or a date.
@Suite struct OTPRealWorldTests {
    @Test(arguments: [
        ("Amazon: Your one-time password is 908172", "908172"),
        ("Your Uber code: 5521. Reply STOP to unsubscribe", "5521"),
        ("Paid 12,500 USD on 23/11/2024. Your code 445566", "445566"),
        ("Your verification code: 4821\nSent at 14:35 on 1403/08/30", "4821"),
        ("Your code: 1234. Valid until 2024-11-23", "1234"),
        ("کد فعالسازی شما ۷۳۸۲۹۱ است", "738291"),
        ("رمز عبور موقت: 58213", "58213"),
        ("بانک سامان\nرمز پویا خرید: ۳۸۴۷۲۱\nمبلغ: ۲,۵۰۰,۰۰۰ ریال\nزمان: ۱۴:۰۵", "384721"),
        ("کد تأیید ورود: 7203", "7203"),
        ("Telegram code: 77412\n\nDo not give this code to anyone", "77412"),
    ] as [(String, String)])
    func findsCode(_ text: String, _ code: String) {
        #expect(OTPExtractor.extract(text) == code)
        let r = OTPExtractor.find(text)!.range
        #expect(OTPExtractor.normalizeDigits((text as NSString).substring(with: r)).filter(\.isNumber) == code)
    }

    @Test(arguments: [
        "Your code is 12",
        "Transfer 5000 toman ref 778899",
        "کد پیگیری: ۱۲۳۴۵۶۷۸۹۰۱۲",
        "Meeting on 2024-11-23 at 14:35, room 4021",
        "Balance: 1,250,000 IRR. Date 1403/08/30",
        "برداشت ۵۰۰۰۰۰ ریال از کارت ۶۰۳۷**۱۲۳۴ مانده ۲۳۴۵۶۷۸ ریال",
        "Your package arrives 23.11.2024",
        "Happy new year 1404!",
    ])
    func noCode(_ text: String) {
        #expect(OTPExtractor.extract(text) == nil)
    }

    /// The code entity shown in the bubble is the same code the notification offers.
    @Test func parsedMessageAgreesWithExtractor() {
        for t in ["رمز پویا: ۸۲۷۱۶۳", "Your verification code is 482913."] {
            #expect(MessageText.parse(t).code == OTPExtractor.extract(t))
        }
    }

    @Test func normalizeDigitsKeepsLength() {
        let s = "۰۱۲۳۴۵۶۷۸۹ ٠١٢٣٤٥٦٧٨٩ abc"
        #expect(OTPExtractor.normalizeDigits(s) == "0123456789 0123456789 abc")
        #expect((OTPExtractor.normalizeDigits(s) as NSString).length == (s as NSString).length, "ranges carry over")
    }
}
