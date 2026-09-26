package app.notomorrow.feature.workout

import app.notomorrow.model.WeightUnit
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Which plates go on each side of the bar for a target weight — 1:1 port of `PlateMath.swift`.
 * Everything is in the user's unit (kg or lb): gyms stock plates in one or the other, so a
 * 100 kg target is never loaded with pound plates.
 */
object PlateMath {

    /** Plates a gym usually has, heaviest first, per unit; the count on the rack is treated as unlimited. */
    fun plates(unit: WeightUnit): List<Double> =
        if (unit == WeightUnit.Kg) listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25) else listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)

    /** Bar choices, the standard men's bar first. */
    fun bars(unit: WeightUnit): List<Double> =
        if (unit == WeightUnit.Kg) listOf(20.0, 15.0, 10.0) else listOf(45.0, 35.0, 15.0)

    /** One plate weight and how many of it go on a side ("2 × 20"). */
    data class PlateGroup(val plate: Double, val count: Int)

    data class Load(
        /** One side of the bar, heaviest first (the other side mirrors it). */
        val perSide: List<Double>,
        /** Bar plus both sides: what the loaded bar actually weighs. */
        val total: Double,
        /** Target minus total: over 0 when the plates cannot make the target exactly. */
        val shortBy: Double,
        /** The target is lighter than the empty bar. */
        val isBelowBar: Boolean,
    ) {
        val isExact: Boolean get() = !isBelowBar && shortBy < 0.001

        /** "2 × 20" style groups for the per-side list, heaviest first. */
        val groups: List<PlateGroup>
            get() {
                val result = mutableListOf<PlateGroup>()
                for (plate in perSide) {
                    val last = result.lastOrNull()
                    if (last != null && last.plate == plate) {
                        result[result.lastIndex] = last.copy(count = last.count + 1)
                    } else {
                        result += PlateGroup(plate, 1)
                    }
                }
                return result
            }
    }

    /**
     * Greedy fill, heaviest plate first, never over the target (exact for standard plate sets).
     * A target at or below the bar loads nothing. Works in hundredths so 1.25 steps add up exactly.
     */
    fun load(target: Double, bar: Double, plates: List<Double>): Load {
        val barUnits = units(bar)
        val targetUnits = units(max(0.0, target))
        var perSideUnits = max(0L, targetUnits - barUnits) / 2
        val perSide = mutableListOf<Double>()
        for (plate in plates.sortedDescending()) {
            val plateUnits = units(plate)
            if (plateUnits <= 0) continue
            while (perSideUnits >= plateUnits) {
                perSide += plate
                perSideUnits -= plateUnits
            }
        }
        val loadedUnits = barUnits + 2 * perSide.sumOf { units(it) }
        return Load(
            perSide = perSide,
            total = loadedUnits / SCALE,
            shortBy = max(0.0, (targetUnits - loadedUnits) / SCALE),
            isBelowBar = targetUnits < barUnits,
        )
    }

    /** Hundredths, rounded half away from zero as Swift's `rounded()` (the inputs are never negative). */
    private fun units(value: Double): Long = (value * SCALE).roundToLong()

    private const val SCALE = 100.0
}
