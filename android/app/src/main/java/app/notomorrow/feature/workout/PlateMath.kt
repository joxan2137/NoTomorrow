package app.notomorrow.feature.workout

import app.notomorrow.model.WeightUnit
import kotlin.math.floor
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

    /** Heaviest target the calculator loads (above any real lift); a bigger number is a typo, not a bar to draw. */
    fun maxTarget(unit: WeightUnit): Double = if (unit == WeightUnit.Kg) 500.0 else 1100.0

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
        /** The target is over the calculator's limit: nothing is loaded. */
        val isOverMax: Boolean = false,
    ) {
        val isExact: Boolean get() = !isBelowBar && !isOverMax && shortBy < 0.001

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
     * A target at or below the bar loads nothing, and so does one over [limit]. Works in hundredths so 1.25 steps add up exactly.
     */
    fun load(target: Double, bar: Double, plates: List<Double>, limit: Double = Double.POSITIVE_INFINITY): Load {
        if (target > limit) return Load(perSide = emptyList(), total = bar, shortBy = 0.0, isBelowBar = false, isOverMax = true)
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

/**
 * Warm-up ramp for an exercise's working weight (the exercise menu's "Add warm-up sets"), in the
 * user's unit — `WarmupPlan` (`PlateMath.swift`). Barbell lifts start with the empty bar;
 * everything else ramps from half the working weight. Weights round down to what plates can make
 * (2.5 kg / 5 lb); steps that land on the same weight are dropped.
 */
object WarmupPlan {

    data class Step(val weight: Double, val reps: Int)

    fun increment(unit: WeightUnit): Double = if (unit == WeightUnit.Kg) 2.5 else 5.0

    fun isBarbell(equipment: String?): Boolean {
        val value = equipment?.lowercase() ?: return false
        return value == "barbell" || value.contains("curl bar")
    }

    fun steps(working: Double, unit: WeightUnit, equipment: String?): List<Step> {
        val step = increment(unit)
        if (working < step * 4) return emptyList()
        val result = mutableListOf<Step>()
        val ramp: List<Pair<Double, Int>>
        if (isBarbell(equipment)) {
            val bar = PlateMath.bars(unit).first()
            if (working <= bar + step) return emptyList()
            result += Step(bar, 10)
            ramp = listOf(0.5 to 5, 0.7 to 3, 0.85 to 1)
        } else {
            ramp = listOf(0.5 to 8, 0.75 to 4)
        }
        for ((ratio, reps) in ramp) {
            val weight = floor(working * ratio / step + 1e-9) * step
            if (weight >= working || weight <= (result.lastOrNull()?.weight ?: 0.0)) continue
            result += Step(weight, reps)
        }
        return result
    }
}
