// GENERATED FILE — DO NOT EDIT.
// Source of truth: design/tokens/*.json
// Regenerate: cd design && npm run tokens (Swift)
import SwiftUI
import CoreGraphics
#if canImport(UIKit)
import UIKit
#endif

public enum Moyu {
    public enum Palette {
        public static let accentContainer = moyuDynamic(light: (0xD9, 0xF0, 0xE8, 1.0000), dark: (0x1C, 0x44, 0x38, 1.0000))
        public static let accentOnPrimary = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0x0B, 0x0D, 0x0E, 1.0000))
        public static let accentPrimary = moyuDynamic(light: (0x1E, 0x7A, 0x68, 1.0000), dark: (0x3F, 0xAE, 0x8C, 1.0000))
        public static let avatar1 = moyuDynamic(light: (0x1E, 0x7A, 0x68, 1.0000), dark: (0x1E, 0x7A, 0x68, 1.0000))
        public static let avatar2 = moyuDynamic(light: (0x4A, 0x5A, 0x8A, 1.0000), dark: (0x4A, 0x5A, 0x8A, 1.0000))
        public static let avatar3 = moyuDynamic(light: (0x8A, 0x6A, 0x4A, 1.0000), dark: (0x8A, 0x6A, 0x4A, 1.0000))
        public static let avatar4 = moyuDynamic(light: (0x6E, 0x4A, 0x8A, 1.0000), dark: (0x6E, 0x4A, 0x8A, 1.0000))
        public static let avatar5 = moyuDynamic(light: (0x4A, 0x7A, 0x8A, 1.0000), dark: (0x4A, 0x7A, 0x8A, 1.0000))
        public static let avatar6 = moyuDynamic(light: (0x8A, 0x4A, 0x5A, 1.0000), dark: (0x8A, 0x4A, 0x5A, 1.0000))
        public static let avatar7 = moyuDynamic(light: (0x5A, 0x8A, 0x4A, 1.0000), dark: (0x5A, 0x8A, 0x4A, 1.0000))
        public static let avatar8 = moyuDynamic(light: (0x8A, 0x7A, 0x4A, 1.0000), dark: (0x8A, 0x7A, 0x4A, 1.0000))
        public static let avatarGlyph = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0xFF, 0xFF, 0xFF, 1.0000))
        public static let borderHairline = moyuDynamic(light: (0xE4, 0xE4, 0xE1, 1.0000), dark: (0x26, 0x29, 0x2C, 1.0000))
        public static let bubblePeer = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0x21, 0x25, 0x28, 1.0000))
        public static let bubbleSelf = moyuDynamic(light: (0xD9, 0xF0, 0xE8, 1.0000), dark: (0x1C, 0x44, 0x38, 1.0000))
        public static let mediaScrim = moyuDynamic(light: (0x00, 0x00, 0x00, 0.4000), dark: (0x00, 0x00, 0x00, 0.5490))
        public static let mediaViewerBackground = moyuDynamic(light: (0x00, 0x00, 0x00, 1.0000), dark: (0x00, 0x00, 0x00, 1.0000))
        public static let qrModule = moyuDynamic(light: (0x00, 0x00, 0x00, 1.0000), dark: (0x00, 0x00, 0x00, 1.0000))
        public static let qrQuietZone = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0xFF, 0xFF, 0xFF, 1.0000))
        public static let shareCardAccent = moyuDynamic(light: (0x1E, 0x7A, 0x68, 1.0000), dark: (0x1E, 0x7A, 0x68, 1.0000))
        public static let shareCardBackground = moyuDynamic(light: (0xF7, 0xF7, 0xF5, 1.0000), dark: (0xF7, 0xF7, 0xF5, 1.0000))
        public static let shareCardHeaderBackground = moyuDynamic(light: (0x1E, 0x7A, 0x68, 1.0000), dark: (0x1E, 0x7A, 0x68, 1.0000))
        public static let shareCardHeaderText = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0xFF, 0xFF, 0xFF, 1.0000))
        public static let shareCardText = moyuDynamic(light: (0x1A, 0x1C, 0x1E, 1.0000), dark: (0x1A, 0x1C, 0x1E, 1.0000))
        public static let shareCardTextSecondary = moyuDynamic(light: (0x5A, 0x60, 0x65, 1.0000), dark: (0x5A, 0x60, 0x65, 1.0000))
        public static let splashBackground = moyuDynamic(light: (0x10, 0x12, 0x14, 1.0000), dark: (0x10, 0x12, 0x14, 1.0000))
        public static let splashDecoy = moyuDynamic(light: (0x26, 0x29, 0x2C, 1.0000), dark: (0x26, 0x29, 0x2C, 1.0000))
        public static let splashMark = moyuDynamic(light: (0x3F, 0xAE, 0x8C, 1.0000), dark: (0x3F, 0xAE, 0x8C, 1.0000))
        public static let statusDanger = moyuDynamic(light: (0xD6, 0x45, 0x45, 1.0000), dark: (0xE0, 0x60, 0x5A, 1.0000))
        public static let statusWarn = moyuDynamic(light: (0xC0, 0x7A, 0x18, 1.0000), dark: (0xD8, 0x9A, 0x4A, 1.0000))
        public static let surfaceBase = moyuDynamic(light: (0xF7, 0xF7, 0xF5, 1.0000), dark: (0x10, 0x12, 0x14, 1.0000))
        public static let surfaceRaised = moyuDynamic(light: (0xFF, 0xFF, 0xFF, 1.0000), dark: (0x1A, 0x1D, 0x1F, 1.0000))
        public static let surfaceSunken = moyuDynamic(light: (0xEF, 0xEF, 0xEC, 1.0000), dark: (0x0B, 0x0D, 0x0E, 1.0000))
        public static let textPrimary = moyuDynamic(light: (0x1A, 0x1C, 0x1E, 1.0000), dark: (0xED, 0xEF, 0xF1, 1.0000))
        public static let textSecondary = moyuDynamic(light: (0x5A, 0x60, 0x65, 1.0000), dark: (0xA2, 0xA8, 0xAD, 1.0000))
        public static let textTertiary = moyuDynamic(light: (0x90, 0x96, 0x9B, 1.0000), dark: (0x6C, 0x73, 0x78, 1.0000))
    }
    public enum Space {
        public static let l: CGFloat = 16
        public static let m: CGFloat = 12
        public static let s: CGFloat = 8
        public static let xl: CGFloat = 24
        public static let xs: CGFloat = 4
        public static let xxl: CGFloat = 32
    }
    public enum Radius {
        public static let bubble: CGFloat = 16
        public static let bubbleTail: CGFloat = 4
        public static let card: CGFloat = 12
        public static let input: CGFloat = 10
        public static let sheet: CGFloat = 20
    }
    public enum Size {
        public static let avatarInline: CGFloat = 28
        public static let avatarList: CGFloat = 44
        public static let avatarProfile: CGFloat = 72
        public static let avatarSwatch: CGFloat = 28
        public static let bubbleMax: CGFloat = 300
        public static let glyphField: CGFloat = 72
        public static let mediaThumbMax: CGFloat = 200
        public static let mediaThumbMin: CGFloat = 72
        public static let playBadge: CGFloat = 44
        public static let progressRing: CGFloat = 36
        public static let progressStroke: CGFloat = 3
        public static let ringSelected: CGFloat = 2
        public static let shareCardHeader: CGFloat = 48
        public static let shareCardHeight: CGFloat = 480
        public static let shareCardPreview: CGFloat = 240
        public static let shareCardQr: CGFloat = 232
        public static let shareCardWidth: CGFloat = 360
        public static let tabIcon: CGFloat = 24
        public static let touchMin: CGFloat = 48
        public static let voiceBubbleMax: CGFloat = 240
        public static let voiceBubbleMin: CGFloat = 72
        public static let voiceOverlay: CGFloat = 160
    }
    public enum FontSize {
        public static let body: CGFloat = 17
        public static let callout: CGFloat = 14
        public static let caption: CGFloat = 12
        public static let display: CGFloat = 28
        public static let mono: CGFloat = 13
        public static let title: CGFloat = 20
    }
    public enum Motion {
        public static let gentle: Double = 360
        public static let quick: Double = 140
        public static let seal: Double = 600
        public static let standard: Double = 220
    }
}

// (r, g, b, alpha) 0-255 通道 + 0-1 alpha;dark 经 UIColor dynamic provider,
// macOS(swift test)上无 UIKit → 回退 light 单值。
private typealias MoyuRGBA = (UInt8, UInt8, UInt8, Double)
private func moyuColor(_ c: MoyuRGBA) -> SwiftUI.Color {
    SwiftUI.Color(red: Double(c.0) / 255.0, green: Double(c.1) / 255.0, blue: Double(c.2) / 255.0, opacity: c.3)
}
private func moyuDynamic(light: MoyuRGBA, dark: MoyuRGBA) -> SwiftUI.Color {
    #if canImport(UIKit)
    return SwiftUI.Color(UIColor { tc in
        let c = tc.userInterfaceStyle == .dark ? dark : light
        return UIColor(red: CGFloat(c.0) / 255.0, green: CGFloat(c.1) / 255.0, blue: CGFloat(c.2) / 255.0, alpha: CGFloat(c.3))
    })
    #else
    return moyuColor(light)
    #endif
}
