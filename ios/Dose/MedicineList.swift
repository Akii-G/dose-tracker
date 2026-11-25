import Foundation

/// Medicines authorised in Italy (AIFA open data, CC BY 4.0): name, strength, form and active
/// ingredient, built into the app by CI from data/medicines-it.tsv. Searched on the phone:
/// nothing is looked up online, so no one learns what you take.
enum MedicineList {
    struct Item: Hashable {
        let name: String
        let strength: String
        let form: String
        let ingredient: String
        let foldedName: String
        let foldedIngredient: String
        var packs: String = ""      // AIC codes of its packs: "012345:045,057"
    }

    static let items: [Item] = {
        guard let url = Bundle.main.url(forResource: "medicines-it", withExtension: "tsv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        var out: [Item] = []
        out.reserveCapacity(23_000)
        for line in text.split(separator: "\n") where !line.hasPrefix("#") {
            let p = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if p.count >= 4 {
                out.append(Item(name: p[0], strength: p[1], form: p[2], ingredient: p[3],
                                foldedName: fold(p[0]), foldedIngredient: fold(p[3]), packs: p.count > 4 ? p[4] : ""))
            }
        }
        return out
    }()

    static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
    }

    /// Names starting with the words first, then ingredients, then names containing them.
    static func search(_ query: String, limit: Int = 30) -> [Item] {
        if let code = Aic.fromText(query) { return Aic.find(items, code).map { [$0] } ?? [] }   // the number on the box
        let q = fold(query.trimmingCharacters(in: .whitespaces))
        guard q.count >= 2 else { return [] }
        var first: [Item] = [], second: [Item] = [], third: [Item] = []
        for item in items {
            if item.foldedName.hasPrefix(q) { first.append(item) }
            else if item.foldedIngredient.hasPrefix(q) { second.append(item) }
            else if item.foldedName.contains(q) || item.foldedIngredient.contains(q) { third.append(item) }
            if first.count >= limit { break }
        }
        return Array((first + second + third).prefix(limit))
    }

    /// The unit that fits a form from the list: drops for "gocce", capsules for "capsula"…
    static func unit(for form: String) -> String {
        let f = form.lowercased()
        if f.contains("gocce") { return "drop" }
        if f.contains("capsul") { return "capsule" }
        if f.contains("bustin") { return "sachet" }
        if f.contains("cerott") { return "patch" }
        if f.contains("sciroppo") || f.contains("soluzione orale") || f.contains("sospensione orale") { return "ml" }
        if f.contains("inala") { return "puff" }
        if f.contains("iniett") { return "injection" }
        return "pill"
    }
}

/// The AIC number (9 digits, "A012345678" on the box) names one pack of one medicine. Boxes carry it
/// as Code 39 ("A012345678"), as Code 32 (the same number in base 32, 6 characters, read as Code 39)
/// and, since 2025, in the GS1 DataMatrix under AI (716). Same rules as the PC (src/dose).
enum Aic {
    private static let code32 = Array("0123456789BCDFGHJKLMNPQRSTUVWXYZ")
    private static let fixed: [String: Int] = ["00": 18, "01": 14, "02": 14, "11": 6, "12": 6, "13": 6, "15": 6, "16": 6, "17": 6]

    /// Typed by hand: "A012345678", "012345678" -> "012345678".
    static func fromText(_ text: String) -> String? {
        let t = text.uppercased().filter { !" .-\t".contains($0) }
        let digits = t.hasPrefix("A") ? String(t.dropFirst()) : t
        return digits.count == 9 && digits.allSatisfy(\.isASCIIDigit) ? digits : nil
    }

    static func fromBarcode(_ payload: String) -> String? {
        var p = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        if p.hasPrefix("]") && p.count > 3 { p = String(p.dropFirst(3)) }
        if let code = fromText(p) { return code }
        let upper = Array(p.uppercased())
        if upper.count == 6 && upper.allSatisfy({ code32.contains($0) }) {
            var n = 0
            for c in upper { n = n * 32 + code32.firstIndex(of: c)! }
            return n < 1_000_000_000 ? String(format: "%09d", n) : nil
        }
        let chars = Array(p)
        var i = 0
        while i < chars.count {
            if chars[i] == "\u{1D}" { i += 1; continue }
            let ai = String(chars[i..<min(i + 2, chars.count)])
            if let len = fixed[ai] { i += 2 + len; continue }
            var end = i
            while end < chars.count && chars[end] != "\u{1D}" { end += 1 }
            let field = String(chars[i..<end])
            if field.hasPrefix("716") {
                let digits = field.dropFirst(3).filter(\.isASCIIDigit)
                guard !digits.isEmpty else { return nil }
                let last = String(digits.suffix(9))
                return String(repeating: "0", count: 9 - last.count) + last
            }
            i = end
        }
        if let r = p.range(of: #"716(\d{9})(?!\d)"#, options: .regularExpression) {
            return String(p[r].dropFirst(3))
        }
        return nil
    }

    /// The medicine of that pack, or of another pack of the same medicine.
    static func find(_ all: [MedicineList.Item], _ code: String) -> MedicineList.Item? {
        let farmaco = String(code.prefix(6)), pack = String(code.dropFirst(6))
        var near: MedicineList.Item?
        for item in all where !item.packs.isEmpty && item.packs.contains(farmaco) {
            for group in item.packs.split(separator: ";") {
                let parts = group.split(separator: ":", maxSplits: 1)
                guard parts.count == 2, parts[0] == farmaco else { continue }
                if parts[1].split(separator: ",").contains(where: { $0 == pack }) { return item }
                if near == nil { near = item }
            }
        }
        return near
    }
}
