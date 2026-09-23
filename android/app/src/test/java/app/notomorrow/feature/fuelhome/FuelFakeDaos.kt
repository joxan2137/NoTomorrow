package app.notomorrow.feature.fuelhome

import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.relation.DayKcal
import app.notomorrow.data.relation.DayProtein
import app.notomorrow.data.relation.MealEntryWithFood
import app.notomorrow.service.BarcodeKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory `food_item`: enough of [FoodDao] for the Fuel row actions (lookups and the usage
 * bump), with the same `useCount + 1` semantics as the SQL.
 */
class FakeFoodDao(initial: List<FoodItemEntity> = emptyList()) : FoodDao {

    val rows = MutableStateFlow(initial)

    override fun observeRecent(limit: Int): Flow<List<FoodItemEntity>> =
        rows.map { list -> list.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(limit) }

    override suspend fun recent(limit: Int): List<FoodItemEntity> =
        rows.value.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(limit)

    override fun observeFavorites(): Flow<List<FoodItemEntity>> =
        rows.map { list -> list.filter { it.isFavorite }.sortedBy { it.name.lowercase() } }

    override suspend fun byId(id: String): FoodItemEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun preferredByBarcode(keys: List<String>): FoodItemEntity? =
        BarcodeKey.preferred(rows.value.filter { it.barcode in keys })

    override fun observeLibrary(): Flow<List<FoodItemEntity>> =
        rows.map { list -> list.sortedWith(compareBy<FoodItemEntity> { it.lastUsedAt == null }.thenByDescending { it.lastUsedAt }) }

    override suspend fun upsert(food: FoodItemEntity) {
        rows.value = rows.value.filterNot { it.id == food.id } + food
    }

    override suspend fun upsertAll(foods: List<FoodItemEntity>) = foods.forEach { upsert(it) }

    override suspend fun bumpUsage(id: String, at: Long) {
        rows.value = rows.value.map { if (it.id == id) it.copy(useCount = it.useCount + 1, lastUsedAt = at) else it }
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        rows.value = rows.value.map { if (it.id == id) it.copy(isFavorite = isFavorite) else it }
    }

    override suspend fun delete(food: FoodItemEntity) {
        rows.value = rows.value.filterNot { it.id == food.id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/**
 * In-memory `meal_entry` that enforces what Room would: a unique primary key and the foreign key
 * to [foods] on insert (so a restore that still names a deleted food fails the test, as it would
 * crash the app).
 */
class FakeMealDao(
    private val foods: FakeFoodDao,
    initial: List<MealEntryEntity> = emptyList(),
) : MealDao {

    val rows = MutableStateFlow(initial)

    private fun withFood(list: List<MealEntryEntity>, foodRows: List<FoodItemEntity>) =
        list.map { entry -> MealEntryWithFood(entry, foodRows.firstOrNull { it.id == entry.foodId }) }

    override fun observeDayRange(from: Long, to: Long): Flow<List<MealEntryWithFood>> =
        combine(rows, foods.rows) { list, foodRows ->
            withFood(list.filter { it.day in from until to }.sortedBy { it.loggedAt }, foodRows)
        }

    override suspend fun entriesForDay(day: Long): List<MealEntryWithFood> =
        withFood(rows.value.filter { it.day == day }.sortedBy { it.loggedAt }, foods.rows.value)

    override fun observeDayEntries(day: Long): Flow<List<MealEntryEntity>> =
        rows.map { list -> list.filter { it.day == day }.sortedBy { it.loggedAt } }

    override suspend fun proteinByDaySince(from: Long): List<DayProtein> =
        rows.value.filter { it.day >= from }.groupBy { it.day }.map { (day, list) -> DayProtein(day, list.sumOf { it.proteinG }) }

    override fun observeProteinByDaySince(from: Long): Flow<List<DayProtein>> =
        rows.map { list -> list.filter { it.day >= from }.groupBy { it.day }.map { (day, l) -> DayProtein(day, l.sumOf { it.proteinG }) } }

    override fun observeKcalByDaySince(from: Long): Flow<List<DayKcal>> =
        rows.map { list -> list.filter { it.day >= from }.groupBy { it.day }.map { (day, l) -> DayKcal(day, l.sumOf { it.kcal }) } }

    override suspend fun entriesSince(from: Long): List<MealEntryEntity> =
        rows.value.filter { it.day >= from }.sortedWith(compareBy({ it.day }, { it.loggedAt }))

    override suspend fun allEntriesWithFood(): List<MealEntryWithFood> =
        withFood(rows.value.sortedWith(compareBy({ it.day }, { it.loggedAt })), foods.rows.value)

    override suspend fun byId(id: String): MealEntryEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(entry: MealEntryEntity) {
        check(rows.value.none { it.id == entry.id }) { "UNIQUE constraint failed: meal_entry.id" }
        entry.foodId?.let { id -> check(foods.rows.value.any { it.id == id }) { "FOREIGN KEY constraint failed" } }
        rows.value = rows.value + entry
    }

    override suspend fun insertAll(entries: List<MealEntryEntity>) = entries.forEach { insert(it) }

    override suspend fun update(entry: MealEntryEntity) {
        rows.value = rows.value.map { if (it.id == entry.id) entry else it }
    }

    override suspend fun delete(entry: MealEntryEntity) = deleteById(entry.id)

    override suspend fun deleteById(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteByIds(ids: List<String>) {
        rows.value = rows.value.filterNot { it.id in ids }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
