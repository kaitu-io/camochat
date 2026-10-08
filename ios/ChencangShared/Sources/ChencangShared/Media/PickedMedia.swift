import Foundation

/// 相册/相机交回来的原始素材(尚未压缩)。
public enum PickedMedia: Equatable, Sendable {
    case image(Data)
    case video(URL)

    /// 多选(spec §3.5 第 4 步):图片合成一条消息(最多 9 张);每个视频各发一条。
    public static func batches(_ picks: [PickedMedia]) -> (images: [Data], videos: [URL]) {
        var images: [Data] = []
        var videos: [URL] = []
        for pick in picks {
            switch pick {
            case let .image(data):
                if images.count < MediaLimits.maxItemsPerFrame { images.append(data) }
            case let .video(url):
                videos.append(url)
            }
        }
        return (images, videos)
    }
}
