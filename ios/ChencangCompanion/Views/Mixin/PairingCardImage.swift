import SwiftUI
import UIKit
import ChencangShared

/// 把一份配对码画成分享卡片图:链接取当前配置的分享站点,标题里的昵称取我自己存的名字(空 → 匿名版)。
@MainActor
enum PairingCardImage {
    static func make(_ share: PairingShare) -> UIImage? {
        let name = MyProfileKeys.storedName(defaults: UserDefaults(suiteName: SharedAppGroupDefaults.suiteName))
        return PairingCardRenderer.image(isResponse: share.isResponse, name: name, link: share.link)
    }
}
