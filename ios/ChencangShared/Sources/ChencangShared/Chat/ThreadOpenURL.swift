import Foundation

/// `camo://thread?peer=<id>&highlight=<messageId>` — deep link into one
/// conversation thread, optionally highlighting one message. Producer:
/// Task 11's Action Extension (after appending to `AppGroupInbox`, it hands
/// the user back to the app already routed into the sender's thread).
/// Consumer: `ChencangCompanionApp.onOpenURL`, first in the chain so it never
/// collides with `camo://pairing` / `camo://record`.
public enum ThreadOpenURL {
    public static func parse(url: URL) -> (peerId: String, highlight: String?)? {
        guard url.scheme == AppURLScheme.name, url.host == "thread",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let peer = items.first(where: { $0.name == "peer" })?.value, !peer.isEmpty
        else { return nil }
        return (peer, items.first(where: { $0.name == "highlight" })?.value)
    }

    public static func make(peerId: String, highlight: String?) -> URL {
        var c = URLComponents()
        c.scheme = AppURLScheme.name; c.host = "thread"
        c.queryItems = [URLQueryItem(name: "peer", value: peerId)]
        if let highlight { c.queryItems?.append(URLQueryItem(name: "highlight", value: highlight)) }
        return c.url!
    }
}
