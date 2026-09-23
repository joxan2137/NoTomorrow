import Foundation
import Observation

/// Debounced Open Food Facts search state for `FoodSearchView`: 500 ms after the last keystroke, ≥ 2 characters.
@Observable
final class FoodSearchModel {
    enum Phase: Equatable {
        case idle
        case loading
        case results([FoodCandidate])
        case empty
        case error(String)
    }

    var query: String = ""
    private(set) var phase: Phase = .idle

    private var searchTask: Task<Void, Never>?
    private var lastQuery: String = ""

    static let debounce: Duration = .milliseconds(500)

    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The Open Food Facts hits to list under the saved foods: those not listed there already (saved foods from
    /// Open Food Facts keep the hit's "off:<code>" id), in OFF's order.
    static func remoteHits(_ hits: [FoodCandidate], excluding savedIDs: Set<String>) -> [FoodCandidate] {
        savedIDs.isEmpty ? hits : hits.filter { !savedIDs.contains($0.id) }
    }
    var canSearch: Bool { trimmedQuery.count >= FoodSearchService.minimumQueryLength }

    /// Called from `.onChange(of: query)`.
    func queryChanged() {
        searchTask?.cancel()
        let q = trimmedQuery
        guard q.count >= FoodSearchService.minimumQueryLength else {
            phase = .idle
            lastQuery = ""
            return
        }
        guard q != lastQuery || phase == .idle else { return }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: Self.debounce)
            guard !Task.isCancelled else { return }
            await self?.run(q)
        }
    }

    func retry() {
        searchTask?.cancel()
        let q = trimmedQuery
        guard q.count >= FoodSearchService.minimumQueryLength else { return }
        searchTask = Task { [weak self] in await self?.run(q) }
    }

    private func run(_ q: String) async {
        await MainActor.run {
            phase = .loading
            lastQuery = q
        }
        do {
            let hits = try await FoodSearchService.shared.search(query: q, locale: FuelText.locale)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                // An answer for a query the field has moved on from never replaces the current query's state.
                guard q == trimmedQuery else { return }
                phase = hits.isEmpty ? .empty : .results(hits)
            }
        } catch is CancellationError {
            return
        } catch FoodSearchError.alreadyInFlight {
            // The same query is still running from a previous keystroke; poll the cache shortly.
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            await run(q)
        } catch {
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard q == trimmedQuery else { return }
                phase = .error(FoodSearchError.message(for: error))
            }
        }
    }
}
