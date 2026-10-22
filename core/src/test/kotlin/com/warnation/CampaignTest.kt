package com.warnation

import com.badlogic.gdx.math.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CampaignTest {
    private val dt = 1f / 60f

    @Test
    fun missionsUnlockInOrderAndProgressIsSaved() {
        val store = mutableMapOf<String, Any?>()
        val campaign = Campaign(memoryPrefs(store))
        assertEquals(0, campaign.nextMission)
        assertTrue(campaign.isUnlocked(0))
        assertFalse(campaign.isUnlocked(1))

        campaign.recordVictory(1)
        assertEquals(0, campaign.completed, "winning a locked mission doesn't count")
        campaign.recordVictory(0)
        campaign.recordVictory(0)
        assertEquals(1, campaign.completed, "replaying a won mission doesn't skip ahead")
        assertTrue(campaign.isCompleted(0))
        assertTrue(campaign.isUnlocked(1))

        val reloaded = Campaign(memoryPrefs(store))
        assertEquals(1, reloaded.nextMission)
    }

    @Test
    fun finishingTheLastMissionCompletesTheCampaign() {
        val campaign = Campaign(memoryPrefs())
        for (i in Campaign.MISSIONS.indices) campaign.recordVictory(i)
        assertTrue(campaign.finished)
        assertEquals(Campaign.MISSIONS.lastIndex, campaign.nextMission)
    }

    @Test
    fun corruptProgressIsClamped() {
        val campaign = Campaign(memoryPrefs(mutableMapOf("campaign_completed" to 99)))
        assertEquals(Campaign.MISSIONS.size, campaign.completed)
    }

    @Test
    fun missionMapsAreTheSameEveryTime() {
        val mission = Campaign.MISSIONS[2]
        fun layout() = Battle(enemySquads = mission.enemies, mapSeed = mission.mapSeed)
            .terrain.obstacles.map { Triple(it.kind, it.col, it.row) }
        assertEquals(layout(), layout())
    }

    @Test
    fun everyMissionDeploysItsRobotsWhereTheSquadCanReachThem() {
        for (mission in Campaign.MISSIONS) {
            val battle = Battle(enemySquads = mission.enemies, mapSeed = mission.mapSeed)
            val robots = battle.soldiers.filter { it.team == Team.ENEMY }
            assertEquals(mission.enemies.sumOf { it.members.size }, robots.size, mission.title)
            for (robot in robots) {
                assertFalse(battle.terrain.isBlockedAt(robot.pos.x, robot.pos.y), "${mission.title}: robot inside obstacle")
                val path = battle.terrain.findPath(battle.playerSpawn, battle.terrain.nearestOpen(robot.pos.x, robot.pos.y), Soldier.RADIUS)
                assertNotNull(path, "${mission.title}: robot at ${robot.pos} unreachable")
            }
        }
    }

    /** Plays each mission with the default squad focusing fire, and prints how often it wins (a balance check). */
    @Test
    fun missionsGetHarderButStayWinnable() {
        val winRates = Campaign.MISSIONS.map { mission ->
            var wins = 0
            repeat(10) {
                val battle = Battle(enemySquads = mission.enemies, mapSeed = mission.mapSeed)
                var t = 0f
                while (battle.outcome == Outcome.PLAYING && t < 600f) {
                    val squad = battle.soldiers.filter { it.team == Team.PLAYER }
                    val enemies = battle.soldiers.filter { it.team == Team.ENEMY }
                    val center = Vector2(squad.map { it.pos.x }.average().toFloat(), squad.map { it.pos.y }.average().toFloat())
                    val focus = enemies.minByOrNull { it.pos.dst(center) }
                    if (focus != null) squad.filter { it.attackTarget == null }.forEach { battle.orderAttack(it, focus) }
                    battle.update(dt)
                    t += dt
                }
                if (battle.outcome == Outcome.VICTORY) wins++
            }
            println("${mission.title}: won $wins/10")
            wins
        }
        assertTrue(winRates.first() >= 8, "the first mission should be easy, won ${winRates.first()}/10")
    }
}
