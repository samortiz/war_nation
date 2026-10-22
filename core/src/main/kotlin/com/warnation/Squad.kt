package com.warnation

import com.badlogic.gdx.Preferences

/** The player's chosen squad and battle record, saved between sessions. */
class Squad(private val prefs: Preferences) {
    private val counts = SoldierType.entries.associateWithTo(linkedMapOf()) { 0 }

    var victories = prefs.getInteger(KEY_VICTORIES, 0)
        private set
    var defeats = prefs.getInteger(KEY_DEFEATS, 0)
        private set

    init {
        val saved = prefs.getString(KEY_SQUAD, "")
        for (entry in saved.split(',')) {
            val (name, count) = entry.split(':').takeIf { it.size == 2 } ?: continue
            val type = SoldierType.entries.firstOrNull { it.name == name } ?: continue
            counts[type] = count.toIntOrNull()?.coerceIn(0, MAX_SIZE) ?: 0
        }
        if (size == 0 || size > MAX_SIZE) counts.putAll(DEFAULT)
    }

    val size get() = counts.values.sum()

    fun count(type: SoldierType) = counts.getValue(type)

    fun canAdd() = size < MAX_SIZE

    fun canRemove(type: SoldierType) = count(type) > 0

    fun add(type: SoldierType) {
        if (!canAdd()) return
        counts[type] = count(type) + 1
        save()
    }

    fun remove(type: SoldierType) {
        if (!canRemove(type)) return
        counts[type] = count(type) - 1
        save()
    }

    /** Members in deployment order, types interleaved so specialists spread across the formation. */
    fun members(): List<SoldierType> {
        val remaining = counts.toMutableMap()
        val result = mutableListOf<SoldierType>()
        while (remaining.values.any { it > 0 }) {
            for (type in SoldierType.entries) {
                if (remaining.getValue(type) > 0) {
                    result.add(type)
                    remaining[type] = remaining.getValue(type) - 1
                }
            }
        }
        return result
    }

    fun recordOutcome(outcome: Outcome) {
        when (outcome) {
            Outcome.VICTORY -> victories++
            Outcome.DEFEAT -> defeats++
            Outcome.PLAYING -> return
        }
        save()
    }

    private fun save() {
        prefs.putString(KEY_SQUAD, counts.entries.joinToString(",") { "${it.key.name}:${it.value}" })
        prefs.putInteger(KEY_VICTORIES, victories)
        prefs.putInteger(KEY_DEFEATS, defeats)
        prefs.flush()
    }

    companion object {
        const val MAX_SIZE = 6
        private const val KEY_SQUAD = "squad"
        private const val KEY_VICTORIES = "victories"
        private const val KEY_DEFEATS = "defeats"

        val DEFAULT = mapOf(
            SoldierType.RIFLEMAN to 3,
            SoldierType.SNIPER to 1,
            SoldierType.GRENADIER to 1,
            SoldierType.ROCKETEER to 1,
        )
    }
}
