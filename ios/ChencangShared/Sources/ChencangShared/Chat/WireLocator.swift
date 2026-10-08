import Foundation
import Chencang

/// R2:从用户粘贴/分享进来的文本里定位真正的 wire。
/// 按行拆分,只看含 🔒 的行;每行从该行第一个 🔒 起截取(兼容微信昵称前缀),去掉行尾空白与收尾引号;
/// **从最后一行往前**逐个试 `decodeWire`,第一个成功者即 wire。
/// R1 两行形态的说明行「🔒 陈仓加密…」解不开,自然被跳过;单行文字密文行为不变。
public enum WireLocator {
    public static func extract(_ text: String, isWire: (String) -> Bool = WireLocator.decodes) -> String? {
        for candidate in IntakeClassifier.lockCandidates(text).reversed() where isWire(candidate) {
            return candidate
        }
        return nil
    }

    public static func decodes(_ candidate: String) -> Bool {
        (try? decodeWire(s: candidate)) != nil
    }
}
