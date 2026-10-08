import Foundation
import Chencang

extension MediaItem {
    /// 封帧用:本地条目 → 绑定 `MediaRef`(帧内字段,spec §1.1)。
    var mediaRef: MediaRef {
        MediaRef(kind: kind.rawValue,
                 durMs: UInt16(clamping: durMs),
                 width: UInt16(clamping: width),
                 height: UInt16(clamping: height),
                 byteLen: UInt32(clamping: byteLen),
                 blobSecret: blobSecret)
    }

    /// 收件用:帧内引用 → 待下载条目(state = pending)。未知 kind 或 secret 非法 → nil(调用方按占位处理)。
    static func received(from refs: [MediaRef]) -> [MediaItem]? {
        var items: [MediaItem] = []
        for (index, ref) in refs.enumerated() {
            guard let kind = MediaKind(rawValue: ref.kind),
                  let blobId = try? mediaBlobId(blobSecret: ref.blobSecret) else { return nil }
            items.append(MediaItem(index: index, kind: kind, durMs: Int(ref.durMs),
                                   width: Int(ref.width), height: Int(ref.height), byteLen: Int(ref.byteLen),
                                   blobSecret: ref.blobSecret, blobId: blobId, state: .pending))
        }
        return items.isEmpty ? nil : items
    }
}
