package com.warnation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SquadTest {
    @Test
    fun startsWithTheDefaultSquad() {
        val squad = Squad(memoryPrefs())
        assertEquals(Squad.MAX_SIZE, squad.size)
        assertEquals(3, squad.count(SoldierType.RIFLEMAN))
        assertFalse(squad.canAdd())
    }

    @Test
    fun squadIsCappedAndCountsNeverGoNegative() {
        val squad = Squad(memoryPrefs())
        squad.add(SoldierType.SNIPER)
        assertEquals(1, squad.count(SoldierType.SNIPER), "full squad rejects additions")

        squad.remove(SoldierType.SNIPER)
        squad.remove(SoldierType.SNIPER)
        assertEquals(0, squad.count(SoldierType.SNIPER))
        assertFalse(squad.canRemove(SoldierType.SNIPER))
        assertTrue(squad.canAdd())
    }

    @Test
    fun choicesAndRecordSurviveARestart() {
        val store = mutableMapOf<String, Any?>()
        Squad(memoryPrefs(store)).apply {
            remove(SoldierType.RIFLEMAN)
            remove(SoldierType.RIFLEMAN)
            add(SoldierType.ROCKETEER)
            recordOutcome(Outcome.VICTORY)
            recordOutcome(Outcome.DEFEAT)
            recordOutcome(Outcome.VICTORY)
        }
        val reloaded = Squad(memoryPrefs(store))
        assertEquals(1, reloaded.count(SoldierType.RIFLEMAN))
        assertEquals(2, reloaded.count(SoldierType.ROCKETEER))
        assertEquals(5, reloaded.size)
        assertEquals(2, reloaded.victories)
        assertEquals(1, reloaded.defeats)
    }

    @Test
    fun corruptSaveFallsBackToDefault() {
        val squad = Squad(memoryPrefs(mutableMapOf("squad" to "RIFLEMAN:99,BOGUS:2,SNIPER:x")))
        assertEquals(Squad.MAX_SIZE, squad.size)
    }

    @Test
    fun membersSpreadSpecialistsThroughTheFormation() {
        val squad = Squad(memoryPrefs())
        assertEquals(
            listOf(
                SoldierType.RIFLEMAN, SoldierType.SNIPER, SoldierType.GRENADIER,
                SoldierType.ROCKETEER, SoldierType.RIFLEMAN, SoldierType.RIFLEMAN,
            ),
            squad.members(),
        )
    }

    @Test
    fun battleDeploysTheChosenSquad() {
        val battle = Battle(withObstacles = false, playerSquad = listOf(SoldierType.SNIPER, SoldierType.GRENADIER))
        assertEquals(
            listOf(SoldierType.SNIPER, SoldierType.GRENADIER),
            battle.soldiers.filter { it.team == Team.PLAYER }.map { it.type },
        )
    }
}
