import SwiftUI
import UIKit
import ChencangShared

/// 把 `ModerationMail.Mail` 草稿交给用户自己的邮件 App,发不发由用户定;App 本身不发任何东西。
/// 系统打不开 `mailto:`(没有邮件 App)时:整份草稿放进剪贴板(格式同 Android),由调用方提示发到哪个地址。
@MainActor
enum MailLauncher {
    static func send(_ mail: ModerationMail.Mail, openURL: OpenURLAction, onCopied: @escaping @MainActor () -> Void) {
        guard let url = mail.mailtoURL() else {
            copy(mail, onCopied: onCopied)
            return
        }
        openURL(url) { accepted in
            if !accepted { copy(mail, onCopied: onCopied) }
        }
    }

    private static func copy(_ mail: ModerationMail.Mail, onCopied: @MainActor () -> Void) {
        UIPasteboard.general.string = mail.fallbackText
        onCopied()
    }
}
