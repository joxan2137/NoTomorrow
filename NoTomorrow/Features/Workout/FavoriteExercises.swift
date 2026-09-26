import Foundation

/// Starred exercises (the picker's long-press "Add to favorites"): a set of exercise ids in `UserDefaults`, so the
/// store schema stays as it is. The local data wipe clears it with the rest of the user's data.
enum FavoriteExercises {
    static let key = "nt.favoriteExercises"

    static func ids(defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: key) ?? [])
    }

    static func isFavorite(_ id: String, defaults: UserDefaults = .standard) -> Bool {
        ids(defaults: defaults).contains(id)
    }

    /// Stars or unstars `id`; returns the favorites afterwards.
    @discardableResult
    static func toggle(_ id: String, defaults: UserDefaults = .standard) -> Set<String> {
        var current = ids(defaults: defaults)
        if current.contains(id) {
            current.remove(id)
        } else {
            current.insert(id)
        }
        if current.isEmpty {
            defaults.removeObject(forKey: key)
        } else {
            defaults.set(current.sorted(), forKey: key)
        }
        return current
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }

    /// The picker's results split for display: with no search text, the favorites among them go in their own
    /// section on top (in the results' own order, so the muscle and equipment filters still apply) and leave the
    /// rest; while searching, everything stays in one list.
    struct Sections<Item> {
        var favorites: [Item]
        var others: [Item]
    }

    static func sections<Item>(_ results: [Item], favorites: Set<String>, isSearching: Bool,
                               id: (Item) -> String) -> Sections<Item> {
        guard !isSearching, !favorites.isEmpty else { return Sections(favorites: [], others: results) }
        var starred: [Item] = []
        var others: [Item] = []
        for item in results {
            if favorites.contains(id(item)) {
                starred.append(item)
            } else {
                others.append(item)
            }
        }
        return Sections(favorites: starred, others: others)
    }
}
