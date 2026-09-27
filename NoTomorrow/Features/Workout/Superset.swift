import Foundation

/// Superset groups over an ordered list of exercises, as `[Int?]` (one group id per exercise, nil = on its own).
/// A superset is a run of neighbouring exercises with the same id; `normalized` keeps the ids consistent after any
/// edit (a lone member leaves its group, ids renumber from 1 in list order).
enum Superset {

    static func normalized(_ groups: [Int?]) -> [Int?] {
        var result = [Int?](repeating: nil, count: groups.count)
        var next = 1
        var i = 0
        while i < groups.count {
            guard let id = groups[i] else { i += 1; continue }
            var end = i
            while end + 1 < groups.count, groups[end + 1] == id { end += 1 }
            if end > i {
                for j in i...end { result[j] = next }
                next += 1
            }
            i = end + 1
        }
        return result
    }

    /// Joins exercise `index` and the one after it (and the rest of that one's superset).
    static func linkWithNext(_ groups: [Int?], at index: Int) -> [Int?] {
        guard groups.indices.contains(index), groups.indices.contains(index + 1) else { return groups }
        var result = groups
        let id = groups[index] ?? groups[index + 1] ?? ((groups.compactMap { $0 }.max() ?? 0) + 1)
        let absorbed = groups[index + 1]
        result[index] = id
        var j = index + 1
        while j < result.count, j == index + 1 || (absorbed != nil && groups[j] == absorbed) {
            result[j] = id
            j += 1
        }
        return normalized(result)
    }

    /// Takes exercise `index` out of its superset.
    static func unlink(_ groups: [Int?], at index: Int) -> [Int?] {
        guard groups.indices.contains(index) else { return groups }
        var result = groups
        result[index] = nil
        return normalized(result)
    }

    static func isLinkedToNext(_ groups: [Int?], at index: Int) -> Bool {
        guard groups.indices.contains(index + 1), let id = groups[index] else { return false }
        return groups[index + 1] == id
    }

    /// "A", "B"… per superset in list order, nil for exercises on their own.
    static func letters(_ groups: [Int?]) -> [String?] {
        let normal = normalized(groups)
        return normal.map { id in
            guard let id, id >= 1, id <= 26 else { return id.map { "\($0)" } }
            return String(UnicodeScalar(UInt8(64 + id)))
        }
    }
}
