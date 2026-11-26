import Foundation
import Testing
@testable import TetherCore

@Suite struct OTPTests {
    @Test(arguments: [
        // English services
        ("Your verification code is 482913. Do not share it.", "482913"),
        ("G-7741 is your Google verification code.", "7741"),
        ("123456 is your Instagram code. Don't share it.", "123456"),
        ("WhatsApp code 123-456. Don't share this code with others", "123456"),
        ("Your Apple ID Code is: 905217. Don't share it with anyone.", "905217"),
        ("Bank: OTP 7731 for payment of $1,250.00 with card ****4821. Valid 2 min.", "7731"),
        // Persian banks and services (Persian digits, amounts, masked cards, times)
        ("رمز پویا: ۸۲۷۱۶۳\nمبلغ: ۱,۲۵۰,۰۰۰ ریال\nکارت: ۶۰۳۷**۱۲۳۴\nزمان اعتبار: ۱۲۰ ثانیه", "827163"),
        ("کد تایید شما: ۵۳۲۱۸", "53218"),
        ("بانک ملت\nمبلغ ۲۵۰۰۰۰۰ ریال\nرمز یکبار مصرف ۴۴۹۱۲۰\nتاریخ ۱۴۰۳/۰۸/۳۰ ساعت ۱۴:۳۵", "449120"),
        ("کد ورود به دیجی‌کالا: 61842\nاین کد را در اختیار دیگران قرار ندهید", "61842"),
        ("رمز دوم پویا\nکارت 6219-86**-****-1234\nمبلغ 1250000 ریال\nرمز: 902114", "902114"),
        ("<#> Snapp: Code: 2851 \\nAbCdEfGh123", "2851"),
    ] as [(String, String)])
    func findsCode(_ text: String, _ code: String) {
        #expect(OTPExtractor.extract(text) == code)
    }

    @Test(arguments: [
        "Meet at 1930 near gate 4",
        "Your balance is 1,250,000 rials",
        "خرید از کارت ۶۰۳۷۹۹۱۸۲۷۳۴۵۶۷۸ مبلغ ۵۰۰۰۰۰ ریال مانده ۱۲۳۴۵۶۷ ریال",
        "Call me on 09121234567 tomorrow",
        "واریز به حساب: ۱۲۵۰۰۰۰۰ ریال",
    ])
    func noCode(_ text: String) {
        #expect(OTPExtractor.extract(text) == nil)
    }

    @Test func rangeCoversTheDigitsInTheOriginal() throws {
        let text = "رمز پویا: ۸۲۷۱۶۳ مبلغ ۱۰۰ ریال"
        let r = try #require(OTPExtractor.find(text)).range
        #expect((text as NSString).substring(with: r) == "۸۲۷۱۶۳")
    }
}

@Suite struct MessageTextTests {
    private func kinds(_ s: String) -> [ParsedMessage.Kind] { MessageText.parse(s).entities.map(\.kind) }

    @Test func links() {
        #expect(kinds("see https://example.com/a?b=1 now") == [.link(URL(string: "https://example.com/a?b=1")!)])
        let bare = MessageText.parse("لینک پرداخت: shaparak.ir/pay و www.digikala.com").entities
        #expect(bare.count == 2)
        #expect(bare.allSatisfy { if case .link = $0.kind { true } else { false } })
    }

    @Test func emailAndPhones() {
        let p = MessageText.parse("Mail support@example.com or call +1 (555) 010-0123 or ۰۹۱۲۱۲۳۴۵۶۷")
        let k = p.entities.map(\.kind)
        #expect(k.contains(.email("support@example.com")))
        #expect(k.contains(.phone("+15550100123")))
        #expect(k.contains(.phone("09121234567")))
    }

    @Test func notPhones() {
        // Card numbers, amounts and short sender codes are not dialable.
        let k = kinds("Card 1111222233334444, amount 1250000, sender 30009014")
        #expect(!k.contains { if case .phone = $0 { true } else { false } })
    }

    @Test func codeWinsOverPhone() {
        let p = MessageText.parse("Your code is 482913")
        #expect(p.code == "482913")
        #expect(p.entities.count == 1)
    }

    @Test func formatting() {
        let p = MessageText.parse("Sale *50% off* today, _only_ online. Use `SAVE10`")
        #expect(p.text == "Sale 50% off today, only online. Use SAVE10")
        #expect(p.styles.map(\.style) == [.bold, .italic, .mono])
        #expect((p.text as NSString).substring(with: p.styles[0].range) == "50% off")
    }

    @Test(arguments: [
        "Dial *140*1# to check your balance",
        "5*3*2 = 30",
        "my_file_name.txt and snake_case",
        "Visit https://ex.com/a_b_c and http://x.ir/*promo*",
        "**",
        "a * b * c",
    ])
    func noFormatting(_ s: String) {
        let p = MessageText.parse(s)
        #expect(p.styles.isEmpty)
        #expect(p.text == s)
    }

    @Test func plainStripsMarkers() {
        #expect(MessageText.plain("*Important:* pay _today_") == "Important: pay today")
        #expect(MessageText.plain("USSD *140*1#") == "USSD *140*1#")
    }

    @Test func direction() {
        #expect(MessageText.isRightToLeft("سلام، لینک: https://x.com"))
        #expect(!MessageText.isRightToLeft("Hello سلام"))
        #expect(MessageText.isRightToLeft("123 کد"))   // digits are weak; the first strong letter decides
        #expect(MessageText.parse("کد: ۱۲۳۴۵").rightToLeft)
    }

    @Test func maskedNumbersAreIsolatedInPersianText() {
        let p = MessageText.parse("کارت: ۶۰۳۷**۱۲۳۴\nکارت 6219-86**-****-1234")
        let runs = p.isolates.map { (p.text as NSString).substring(with: $0) }
        #expect(runs == ["۶۰۳۷**۱۲۳۴", "6219-86**-****-1234"])
        #expect(MessageText.parse("Card 6037**1234").isolates.isEmpty) // left-to-right text needs none
    }

    @Test func rangesShiftAfterMarkers() {
        let p = MessageText.parse("*Hi* visit example.com")
        let link = p.entities.first { if case .link = $0.kind { true } else { false } }
        #expect(link.map { (p.text as NSString).substring(with: $0.range) } == "example.com")
    }
}
