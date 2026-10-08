import Foundation

/// 逐条解码的数组:坏条目跳过,不连累同一文件/同一收件箱里的其它记录。
/// (DR 棘轮已推进的消息无法重解,一条坏记录绝不能让整批丢失。)
struct LossyArray<Element: Decodable>: Decodable {
    let elements: [Element]

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var out: [Element] = []
        while !container.isAtEnd {
            if let element = try? container.decode(Element.self) {
                out.append(element)
            } else {
                _ = try? container.decode(Skip.self)   // 不读任何字段,只为让游标前进一格
            }
        }
        elements = out
    }

    private struct Skip: Decodable {
        init(from decoder: Decoder) throws {}
    }
}
