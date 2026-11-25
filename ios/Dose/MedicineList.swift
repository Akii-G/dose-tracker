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
    }

    static let items: [Item] = {
        guard let url = Bundle.main.url(forResource: "medicines-it", withExtension: "tsv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        var out: [Item] = []
        out.reserveCapacity(23_000)
        for line in text.split(separator: "\n") where !line.hasPrefix("#") {
            let p = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if p.count == 4 {
                out.append(Item(name: p[0], strength: p[1], form: p[2], ingredient: p[3],
                                foldedName: fold(p[0]), foldedIngredient: fold(p[3])))
            }
        }
        return out
    }()

    static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
    }

    /// Names starting with the words first, then ingredients, then names containing them.
    static func search(_ query: String, limit: Int = 30) -> [Item] {
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
