import CoreGraphics
import Foundation

/// 开屏动画用的极简 SVG 路径解析器：只认绝对坐标的 M/L/H/V/Q/C/A/Z
/// （猫与字标的路径数据只用到这些）。命令字母后可省略重复（M 后续坐标按 L 处理），
/// 与 SVG 规范一致。遇到不认识的命令或残缺的参数直接报错，不悄悄画错。
public enum SVGPath {
    public enum ParseError: Error, Equatable {
        case unsupportedCommand(Character)
        case missingNumber(command: Character)
        case numberWithoutCommand
    }

    public static func parse(_ d: String) throws -> CGPath {
        let path = CGMutablePath()
        var scanner = Tokenizer(d)
        var command: Character?
        var current = CGPoint.zero
        var subpathStart = CGPoint.zero

        while let token = scanner.next() {
            switch token {
            case let .command(c):
                command = c
                if c == "Z" {
                    path.closeSubpath()
                    current = subpathStart
                    continue
                }
                guard "MLHVQCA".contains(c) else { throw ParseError.unsupportedCommand(c) }
            case .number:
                // 同一命令的又一组参数（隐式重复）。
                guard let c = command, c != "Z" else { throw ParseError.numberWithoutCommand }
                scanner.pushBack(token)
                if c == "M" { command = "L" }
            }

            guard let c = command else { throw ParseError.numberWithoutCommand }
            func num() throws -> CGFloat {
                guard let v = scanner.nextNumber() else { throw ParseError.missingNumber(command: c) }
                return v
            }
            switch c {
            case "M":
                current = CGPoint(x: try num(), y: try num())
                subpathStart = current
                path.move(to: current)
            case "L":
                current = CGPoint(x: try num(), y: try num())
                path.addLine(to: current)
            case "H":
                current = CGPoint(x: try num(), y: current.y)
                path.addLine(to: current)
            case "V":
                current = CGPoint(x: current.x, y: try num())
                path.addLine(to: current)
            case "Q":
                // 二次升三次（数学上等价）：SwiftUI Canvas 画二次曲线时会把部分曲线画成直线
                // （实测瞳孔少了半边），三次曲线没有这个问题，系统启动页的 SVG 渲染也与之一致。
                let q = CGPoint(x: try num(), y: try num())
                let end = CGPoint(x: try num(), y: try num())
                path.addCurve(to: end,
                              control1: CGPoint(x: current.x + 2 / 3 * (q.x - current.x), y: current.y + 2 / 3 * (q.y - current.y)),
                              control2: CGPoint(x: end.x + 2 / 3 * (q.x - end.x), y: end.y + 2 / 3 * (q.y - end.y)))
                current = end
            case "C":
                let c1 = CGPoint(x: try num(), y: try num())
                let c2 = CGPoint(x: try num(), y: try num())
                current = CGPoint(x: try num(), y: try num())
                path.addCurve(to: current, control1: c1, control2: c2)
            case "A":
                let rx = try num(), ry = try num(), rotation = try num()
                let largeArc = try num() != 0, sweep = try num() != 0
                let end = CGPoint(x: try num(), y: try num())
                addArc(to: path, from: current, to: end, rx: rx, ry: ry,
                       rotationDegrees: rotation, largeArc: largeArc, sweep: sweep)
                current = end
            default:
                throw ParseError.unsupportedCommand(c)
            }
        }
        return path
    }

    /// SVG 椭圆弧（端点参数化，规范附录 F.6.5）→ 中心参数化 → 每段 ≤90° 的三次贝塞尔。
    private static func addArc(to path: CGMutablePath, from p0: CGPoint, to p1: CGPoint,
                               rx rx0: CGFloat, ry ry0: CGFloat, rotationDegrees: CGFloat,
                               largeArc: Bool, sweep: Bool) {
        if p0 == p1 { return }
        var rx = abs(rx0), ry = abs(ry0)
        if rx == 0 || ry == 0 { path.addLine(to: p1); return }
        let phi = rotationDegrees * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1p = cosPhi * dx + sinPhi * dy
        let y1p = -sinPhi * dx + cosPhi * dy
        // 半径不够跨过两端点时按规范等比放大。
        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(max(0, num / den))
        if largeArc == sweep { coef = -coef }
        let cxp = coef * rx * y1p / ry
        let cyp = -coef * ry * x1p / rx
        let cx = cosPhi * cxp - sinPhi * cyp + (p0.x + p1.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (p0.y + p1.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        }
        let theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi }
        if sweep && delta < 0 { delta += 2 * .pi }

        let segments = max(1, Int(ceil(abs(delta) / (.pi / 2) - 1e-9)))
        let step = delta / CGFloat(segments)
        let k = 4.0 / 3.0 * tan(step / 4)
        func point(_ t: CGFloat) -> CGPoint {
            let x = rx * cos(t), y = ry * sin(t)
            return CGPoint(x: cx + cosPhi * x - sinPhi * y, y: cy + sinPhi * x + cosPhi * y)
        }
        func derivative(_ t: CGFloat) -> CGPoint {
            let x = -rx * sin(t), y = ry * cos(t)
            return CGPoint(x: cosPhi * x - sinPhi * y, y: sinPhi * x + cosPhi * y)
        }
        var t = theta1
        for i in 0..<segments {
            let t2 = t + step
            let a = point(t), b = point(t2)
            let da = derivative(t), db = derivative(t2)
            let end = i == segments - 1 ? p1 : b
            path.addCurve(to: end,
                          control1: CGPoint(x: a.x + k * da.x, y: a.y + k * da.y),
                          control2: CGPoint(x: b.x - k * db.x, y: b.y - k * db.y))
            t = t2
        }
    }

    private enum Token {
        case command(Character)
        case number(CGFloat)
    }

    private struct Tokenizer {
        private let chars: [Character]
        private var index = 0
        private var pending: Token?

        init(_ s: String) { chars = Array(s) }

        mutating func pushBack(_ token: Token) { pending = token }

        mutating func nextNumber() -> CGFloat? {
            guard let token = next() else { return nil }
            if case let .number(v) = token { return v }
            pushBack(token)
            return nil
        }

        mutating func next() -> Token? {
            if let p = pending { pending = nil; return p }
            while index < chars.count, chars[index] == " " || chars[index] == "," || chars[index].isNewline || chars[index] == "\t" {
                index += 1
            }
            guard index < chars.count else { return nil }
            let c = chars[index]
            if c.isLetter && c != "e" && c != "E" {
                index += 1
                return .command(c)
            }
            // 数字：可选符号、整数/小数部分、可选指数。
            let start = index
            if chars[index] == "-" || chars[index] == "+" { index += 1 }
            var seenDot = false
            while index < chars.count {
                let ch = chars[index]
                if ch.isASCII && ch.isNumber { index += 1 }
                else if ch == "." && !seenDot { seenDot = true; index += 1 }
                else { break }
            }
            if index < chars.count, chars[index] == "e" || chars[index] == "E" {
                index += 1
                if index < chars.count, chars[index] == "-" || chars[index] == "+" { index += 1 }
                while index < chars.count, chars[index].isASCII, chars[index].isNumber { index += 1 }
            }
            guard index > start, let v = Double(String(chars[start..<index])) else {
                // 无法识别的字符：当作命令交给上层报错。
                index = max(index, start + 1)
                return .command(c)
            }
            return .number(CGFloat(v))
        }
    }
}
