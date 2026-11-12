package dev.steamshelf.server.library

import dev.steamshelf.data.LibraryRepository
import dev.steamshelf.model.GameItem
import dev.steamshelf.model.LibraryQuery
import dev.steamshelf.model.LibraryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class TagOption(val id: Int, val name: String, val count: Int)

/** Everything the library page shows for one [LibraryView]; the same numbers as the app's LibraryViewModel. */
data class LibraryPage(
    val view: LibraryView,
    /** Every owned game, hidden ones included. */
    val totalCount: Int,
    /** Games the view can show at most: hidden ones only count when "show hidden" is on. */
    val shownTotal: Int,
    /** Visible games whose HLTB match should be checked. */
    val reviewCount: Int,
    val items: List<GameItem>,
    val stats: LibraryStats,
    val tags: List<TagOption>,
) {
    companion object {
        const val PAGE_SIZE = 60

        suspend fun build(repository: LibraryRepository, view: LibraryView): LibraryPage {
            repository.awaitLoaded()
            val all = repository.items.first()
            val tagNames = repository.tagNames.value
            return withContext(Dispatchers.Default) {
                val visible = LibraryQuery.apply(all, view.filter, view.query, view.prefs.sort)
                val shown = if (view.filter.showHidden) all else all.filterNot { it.hidden }
                LibraryPage(
                    view = view,
                    totalCount = all.size,
                    shownTotal = shown.size,
                    reviewCount = shown.count { it.hltbNeedsReview },
                    items = visible,
                    stats = LibraryStats.of(visible),
                    tags = tagOptions(all, view.filter.tags, tagNames),
                )
            }
        }

        private fun tagOptions(items: List<GameItem>, selected: Set<Int>, names: Map<Int, String>): List<TagOption> {
            val counts = HashMap<Int, Int>()
            items.forEach { item -> item.store?.tagIds?.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            return counts.entries
                .filter { (id, count) -> names.containsKey(id) && (count >= 2 || id in selected) }
                .sortedByDescending { it.value }
                .map { (id, count) -> TagOption(id, names.getValue(id), count) }
        }
    }
}
