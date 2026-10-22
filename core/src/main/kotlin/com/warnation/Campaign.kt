package com.warnation

import com.badlogic.gdx.Preferences
import com.badlogic.gdx.math.Vector2
import com.warnation.SoldierType.GRENADIER
import com.warnation.SoldierType.RIFLEMAN
import com.warnation.SoldierType.ROCKETEER
import com.warnation.SoldierType.SNIPER

/** A robot squad: where it's posted and who's in it, in spawn order. */
class EnemySquad(val post: Vector2, val members: List<SoldierType>) {
    constructor(x: Float, y: Float, vararg members: SoldierType) : this(Vector2(x, y), members.toList())
}

/** A campaign battle: a fixed map (from [mapSeed]) and robot garrison. */
class Mission(val title: String, val briefing: String, val mapSeed: Long, val enemies: List<EnemySquad>)

/** The campaign's missions and how far the player has got, saved between sessions. */
class Campaign(private val prefs: Preferences) {
    /** Number of missions won so far; the next one is unlocked. */
    var completed = prefs.getInteger(KEY_COMPLETED, 0).coerceIn(0, MISSIONS.size)
        private set

    val finished get() = completed == MISSIONS.size

    /** The mission to play next: the first unbeaten one, or the last once the campaign is done. */
    val nextMission get() = minOf(completed, MISSIONS.lastIndex)

    fun isUnlocked(index: Int) = index in MISSIONS.indices && index <= completed

    fun isCompleted(index: Int) = index < completed

    fun recordVictory(index: Int) {
        if (index != completed) return
        completed++
        prefs.putInteger(KEY_COMPLETED, completed)
        prefs.flush()
    }

    companion object {
        private const val KEY_COMPLETED = "campaign_completed"

        val MISSIONS = listOf(
            Mission(
                "First Contact",
                "Two robot patrols are sweeping the valley. Find them before they find you.",
                mapSeed = 1101,
                enemies = listOf(
                    EnemySquad(1300f, 1000f, RIFLEMAN, RIFLEMAN),
                    EnemySquad(2100f, 1500f, RIFLEMAN, RIFLEMAN),
                ),
            ),
            Mission(
                "Overwatch",
                "Robot snipers hold the high ground. Close the distance behind cover.",
                mapSeed = 2202,
                enemies = listOf(
                    EnemySquad(1350f, 1150f, RIFLEMAN, SNIPER),
                    EnemySquad(2150f, 700f, SNIPER, RIFLEMAN),
                    EnemySquad(1900f, 1600f, SNIPER),
                ),
            ),
            Mission(
                "Grenade Alley",
                "Grenadiers will lob over any wall. Keep moving and stay spread out.",
                mapSeed = 3303,
                enemies = listOf(
                    EnemySquad(1250f, 1100f, RIFLEMAN, GRENADIER),
                    EnemySquad(2150f, 650f, RIFLEMAN, RIFLEMAN, GRENADIER),
                    EnemySquad(1800f, 1600f, GRENADIER, RIFLEMAN),
                ),
            ),
            Mission(
                "Heavy Metal",
                "Rocket teams guard the depot. Their rockets are slow: sidestep and strike.",
                mapSeed = 4404,
                enemies = listOf(
                    EnemySquad(1200f, 1050f, RIFLEMAN, ROCKETEER),
                    EnemySquad(2150f, 700f, ROCKETEER, RIFLEMAN),
                    EnemySquad(1050f, 1600f, RIFLEMAN, ROCKETEER),
                    EnemySquad(2150f, 1550f, ROCKETEER, RIFLEMAN),
                ),
            ),
            Mission(
                "Crossfire",
                "Four mixed squads cover each other. Pick them off one at a time.",
                mapSeed = 5505,
                enemies = listOf(
                    EnemySquad(1250f, 1050f, RIFLEMAN, SNIPER),
                    EnemySquad(2150f, 650f, RIFLEMAN, RIFLEMAN, GRENADIER),
                    EnemySquad(1000f, 1600f, RIFLEMAN, ROCKETEER),
                    EnemySquad(2150f, 1550f, RIFLEMAN, SNIPER, ROCKETEER),
                ),
            ),
            Mission(
                "The Long Ridge",
                "The robots have dug in along the ridge. Flank where their cones don't reach.",
                mapSeed = 6606,
                enemies = listOf(
                    EnemySquad(1150f, 1150f, RIFLEMAN, RIFLEMAN, SNIPER),
                    EnemySquad(1700f, 800f, RIFLEMAN, GRENADIER),
                    EnemySquad(2250f, 600f, SNIPER, ROCKETEER),
                    EnemySquad(1000f, 1650f, RIFLEMAN, GRENADIER),
                    EnemySquad(2150f, 1550f, RIFLEMAN, RIFLEMAN, ROCKETEER),
                ),
            ),
            Mission(
                "Iron Wall",
                "A heavy garrison blocks the pass. Every squad will answer a call for help.",
                mapSeed = 7707,
                enemies = listOf(
                    EnemySquad(1200f, 1050f, RIFLEMAN, RIFLEMAN, GRENADIER),
                    EnemySquad(1900f, 650f, SNIPER, ROCKETEER),
                    EnemySquad(1000f, 1650f, RIFLEMAN, ROCKETEER),
                    EnemySquad(1700f, 1300f, RIFLEMAN, RIFLEMAN, GRENADIER),
                    EnemySquad(2250f, 1600f, ROCKETEER, SNIPER, RIFLEMAN),
                ),
            ),
            Mission(
                "Robot Nation",
                "The robot command post. Everything they have left stands between you and victory.",
                mapSeed = 8808,
                enemies = listOf(
                    EnemySquad(1150f, 1100f, RIFLEMAN, RIFLEMAN, SNIPER),
                    EnemySquad(1800f, 600f, RIFLEMAN, GRENADIER, ROCKETEER),
                    EnemySquad(950f, 1650f, RIFLEMAN, GRENADIER),
                    EnemySquad(1650f, 1350f, SNIPER, ROCKETEER, RIFLEMAN),
                    EnemySquad(2300f, 1000f, RIFLEMAN, ROCKETEER),
                    EnemySquad(2250f, 1650f, SNIPER, GRENADIER, RIFLEMAN),
                ),
            ),
        )
    }
}
