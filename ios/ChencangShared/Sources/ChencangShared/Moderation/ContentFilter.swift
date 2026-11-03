import Foundation

/// Local bad-word filter for incoming text. Words containing CJK characters match as
/// substrings; purely Latin words match whole words only. Case-insensitive.
public struct ContentFilter {
    private let cjkWords: [String]
    private let latinRegex: NSRegularExpression?

    public init(words: [String]) {
        var seen = Set<String>()
        let lowered = words
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        let isCjk: (String) -> Bool = { w in
            w.unicodeScalars.contains { (0x4E00...0x9FFF).contains($0.value) || (0x3400...0x4DBF).contains($0.value) || (0x20000...0x2A6DF).contains($0.value) }
        }
        cjkWords = lowered.filter(isCjk)
        let latin = lowered.filter { !isCjk($0) }
        if latin.isEmpty {
            latinRegex = nil
        } else {
            let alt = latin.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|")
            latinRegex = try? NSRegularExpression(
                pattern: "(?<![\\p{L}\\p{N}_])(?:\(alt))(?![\\p{L}\\p{N}_])")
        }
    }

    public func isFlagged(_ text: String) -> Bool {
        if text.isEmpty { return false }
        let t = text.lowercased()
        if cjkWords.contains(where: { t.contains($0) }) { return true }
        guard let re = latinRegex else { return false }
        return re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }

    public static func parse(_ raw: String) -> [String] {
        raw.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
    }

    public static let shared: ContentFilter = {
        guard let url = Bundle.module.url(forResource: "moderation_wordlist", withExtension: "txt"),
              let raw = try? String(contentsOf: url, encoding: .utf8) else {
            return ContentFilter(words: [])
        }
        return ContentFilter(words: parse(raw))
    }()
}
