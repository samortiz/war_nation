package com.warnation

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleTest {
    private val dt = 1f / 60f

    private fun tileCenter(c: Int, r: Int) = Vector2(Terrain.center(c), Terrain.center(r))

    /** An obstacle-free battle with only the given soldiers. */
    private fun emptyBattle(vararg soldiers: Soldier) = Battle(withObstacles = false).apply {
        this.soldiers.clear()
        this.soldiers.addAll(soldiers)
    }

    @Test
    fun squadThatAttacksEnemyByEnemyCanWin() {
        var wins = 0
        repeat(20) {
            val battle = Battle()
            var t = 0f
            while (battle.outcome == Outcome.PLAYING && t < 600f) {
                // Focus fire: every idle soldier attacks the enemy closest to the squad.
                val squad = battle.soldiers.filter { it.team == Team.PLAYER }
                val enemies = battle.soldiers.filter { it.team == Team.ENEMY }
                val center = Vector2(squad.map { it.pos.x }.average().toFloat(), squad.map { it.pos.y }.average().toFloat())
                val focus = enemies.minByOrNull { it.pos.dst(center) }
                if (focus != null) squad.filter { it.attackTarget == null }.forEach { battle.orderAttack(it, focus) }
                battle.update(dt)
                for (s in battle.soldiers) assertFalse(battle.terrain.isBlockedAt(s.pos.x, s.pos.y), "soldier inside obstacle at ${s.pos}")
                t += dt
            }
            assertNotEquals(Outcome.PLAYING, battle.outcome, "battle should finish")
            if (battle.outcome == Outcome.VICTORY) wins++
            println("battle ${it + 1}: ${battle.outcome} in ${t.toInt()}s, survivors ${battle.count(Team.PLAYER)}")
        }
        assertTrue(wins >= 8, "focused squad should often win, won $wins/20")
    }

    @Test
    fun enemiesAttackSoldiersThatWanderIntoThem() {
        val enemy = Soldier(Team.ENEMY, 1000f, 1000f)
        enemy.angle = 0f
        val player = Soldier(Team.PLAYER, 1300f, 1000f)
        val battle = emptyBattle(enemy, player)
        repeat(60 * 3) { battle.update(dt) }
        assertEquals(player, enemy.aggroTarget)
        assertTrue(player.hp < player.maxHp, "enemy should have shot the player")
    }

    @Test
    fun moveOrderReachesDestination() {
        val s = Soldier(Team.PLAYER, 300f, 300f)
        val battle = emptyBattle(s, Soldier(Team.ENEMY, 2400f, 1800f))
        val dest = Vector2(450f, 350f)
        battle.orderMove(s, dest)
        repeat(120) { battle.update(dt) }
        assertTrue(s.pos.dst(dest) < 30f, "soldier at ${s.pos}, expected near $dest")
    }

    @Test
    fun soldiersWalkAroundWalls() {
        val s = Soldier(Team.PLAYER, Terrain.center(6), Terrain.center(10))
        val battle = emptyBattle(s, Soldier(Team.ENEMY, 2400f, 1800f))
        for (r in 4..16) battle.terrain.addObstacle(ObstacleKind.ROCK, 10, r)
        val dest = tileCenter(14, 10)

        val path = battle.terrain.findPath(s.pos, dest, Soldier.RADIUS)
        assertNotNull(path)
        assertTrue(path.size >= 2, "path should bend around the wall: $path")

        battle.orderMove(s, dest)
        repeat(60 * 15) {
            battle.update(dt)
            assertFalse(battle.terrain.isBlockedAt(s.pos.x, s.pos.y), "walked into the wall at ${s.pos}")
        }
        assertTrue(s.pos.dst(dest) < 30f, "soldier at ${s.pos}, expected near $dest")
    }

    @Test
    fun movingOntoAnObstacleStopsNextToIt() {
        val s = Soldier(Team.PLAYER, 300f, 300f)
        val battle = emptyBattle(s, Soldier(Team.ENEMY, 2400f, 1800f))
        battle.terrain.addObstacle(ObstacleKind.CRATE, 8, 8)
        battle.orderMove(s, tileCenter(8, 8))
        assertFalse(battle.terrain.isBlockedAt(s.moveTarget!!.x, s.moveTarget!!.y))
        repeat(60 * 10) { battle.update(dt) }
        assertEquals(null, s.moveTarget, "order should complete")
    }

    @Test
    fun wallsBlockSightAndShooting() {
        val player = Soldier(Team.PLAYER, Terrain.center(8), Terrain.center(10))
        val enemy = Soldier(Team.ENEMY, Terrain.center(12), Terrain.center(10))
        val battle = emptyBattle(player, enemy)
        for (r in 0 until Terrain.ROWS - 1) for (c in 9..11) battle.terrain.addObstacle(ObstacleKind.ROCK, c, r)

        assertFalse(battle.canSee(player, enemy))
        repeat(60 * 5) { battle.update(dt) }
        assertEquals(player.maxHp, player.hp)
        assertEquals(enemy.maxHp, enemy.hp)
    }

    @Test
    fun coverOnlyProtectsFromTheCoveredSide() {
        val terrain = Terrain()
        terrain.addObstacle(ObstacleKind.ROCK, 10, 10)
        // Hugging the rock's left face.
        val target = Vector2(10 * Terrain.TILE - Soldier.RADIUS - 1f, Terrain.center(10))

        assertTrue(terrain.inCover(target, Vector2(target.x + 300f, target.y + 150f)), "shooter beyond the rock")
        assertFalse(terrain.inCover(target, Vector2(target.x - 300f, target.y)), "shooter on the open side")
        assertFalse(terrain.inCover(Vector2(target.x - 120f, target.y), Vector2(target.x + 300f, target.y + 150f)), "too far from the rock")
    }

    @Test
    fun soldiersCanPeekPastCoverTheyAreHugging() {
        val terrain = Terrain()
        terrain.addObstacle(ObstacleKind.ROCK, 10, 10)
        val hugger = Vector2(10 * Terrain.TILE - Soldier.RADIUS - 1f, 10 * Terrain.TILE + 20f)
        val shooter = Vector2(hugger.x + 400f, hugger.y - 300f)
        assertTrue(terrain.lineOfSight(hugger.x, hugger.y, shooter.x, shooter.y, Battle.PEEK_DISTANCE))
        assertFalse(terrain.lineOfSight(hugger.x, hugger.y, shooter.x, shooter.y))
    }

    @Test
    fun generatedMapsKeepEveryOpenTileReachable() {
        repeat(10) {
            val battle = Battle()
            val terrain = battle.terrain
            assertTrue(terrain.obstacles.size > 40, "map should have obstacles, got ${terrain.obstacles.size}")
            for (s in battle.soldiers) assertFalse(terrain.isBlockedAt(s.pos.x, s.pos.y), "spawned inside obstacle")
            val from = battle.playerSpawn
            for (c in 0 until Terrain.COLS) for (r in 0 until Terrain.ROWS) {
                if (terrain.isBlocked(c, r)) continue
                assertNotNull(terrain.findPath(from, tileCenter(c, r), Soldier.RADIUS), "tile $c,$r unreachable")
            }
        }
    }

    @Test
    fun groupMoveSpreadsSoldiersIntoFormation() {
        val battle = Battle()
        val squad = battle.soldiers.filter { it.team == Team.PLAYER }
        val dest = Vector2(900f, 700f)
        battle.orderGroupMove(squad, dest)

        val slots = squad.map { it.moveTarget!! }
        for (slot in slots) assertTrue(battle.terrain.fits(slot, Soldier.RADIUS), "slot $slot overlaps an obstacle")
        for (i in slots.indices) for (j in i + 1 until slots.size) {
            assertTrue(slots[i].dst(slots[j]) >= Battle.FORMATION_SPACING - 1f, "slots $i and $j overlap")
        }

        battle.soldiers.removeAll { it.team == Team.ENEMY }
        battle.soldiers.add(Soldier(Team.ENEMY, 2500f, 1850f))
        repeat(60 * 20) { battle.update(dt) }
        for ((s, slot) in squad.zip(slots)) assertTrue(s.pos.dst(slot) < 30f, "soldier at ${s.pos}, slot $slot")
    }

    @Test
    fun grenadiersLobOverWallsAtTargetsASquadmateSpots() {
        // Grenadier behind a short wall; the enemy is beyond it and stationary.
        val grenadier = Soldier(Team.PLAYER, Terrain.center(8), Terrain.center(10), SoldierType.GRENADIER)
        val enemy = Soldier(Team.ENEMY, Terrain.center(12), Terrain.center(10))
        val battle = emptyBattle(grenadier, enemy)
        for (r in 8..12) battle.terrain.addObstacle(ObstacleKind.ROCK, 10, r)
        enemy.patrol.clear()
        assertFalse(battle.canSee(grenadier, enemy))

        // Alone, the grenadier can't see the target and holds fire.
        assertFalse(battle.canTarget(grenadier, enemy))

        // A spotter with a clear view lets the grenadier fire over the wall.
        val spotter = Soldier(Team.PLAYER, Terrain.center(12), Terrain.center(15))
        spotter.angle = -90f
        spotter.hp = 10_000f
        battle.soldiers.add(spotter)
        assertTrue(battle.canTarget(grenadier, enemy))
        repeat(60 * 3) { battle.update(dt) }
        assertTrue(battle.explosions.isNotEmpty(), "grenade should have exploded")
    }

    @Test
    fun scoutsSpotForGrenadiersFromFartherAway() {
        val grenadier = Soldier(Team.PLAYER, Terrain.center(8), Terrain.center(10), SoldierType.GRENADIER)
        val enemy = Soldier(Team.ENEMY, Terrain.center(12), Terrain.center(10))
        val battle = emptyBattle(grenadier, enemy)
        for (r in 8..12) battle.terrain.addObstacle(ObstacleKind.ROCK, 10, r)

        // 450 away from the enemy: too far for a rifleman to spot, close enough for a scout.
        for ((type, spots) in listOf(SoldierType.RIFLEMAN to false, SoldierType.SCOUT to true)) {
            val spotter = Soldier(Team.PLAYER, enemy.pos.x, enemy.pos.y - 450f, type)
            spotter.angle = 90f
            battle.soldiers.add(spotter)
            assertTrue(battle.canSee(spotter, enemy))
            assertEquals(spots, battle.canTarget(grenadier, enemy), "spotted by a $type")
            battle.soldiers.remove(spotter)
        }
    }

    @Test
    fun rocketsSplashGroupedEnemies() {
        val rocketeer = Soldier(Team.PLAYER, 600f, 600f, SoldierType.ROCKETEER)
        val a = Soldier(Team.ENEMY, 900f, 600f)
        val b = Soldier(Team.ENEMY, 900f, 650f)
        val battle = emptyBattle(rocketeer, a, b)
        rocketeer.cooldown = 0f
        battle.orderAttack(rocketeer, a)
        repeat(60) { battle.update(dt) }
        assertTrue(a.hp < a.maxHp && b.hp < b.maxHp, "both should take blast damage: a=${a.hp} b=${b.hp}")
    }

    @Test
    fun snipersOutrangeRiflemen() {
        val sniper = Soldier(Team.PLAYER, 600f, 600f, SoldierType.SNIPER)
        sniper.angle = 0f
        val rifleman = Soldier(Team.ENEMY, 1040f, 600f)
        val battle = emptyBattle(sniper, rifleman)
        rifleman.patrol.clear()
        sniper.cooldown = 0f
        repeat(30) { battle.update(dt) }
        assertTrue(rifleman.hp < rifleman.maxHp, "sniper should fire from beyond rifle range")
        assertEquals(sniper.maxHp, sniper.hp)
    }

    @Test
    fun soldiersReportWhoTheyAreFiringAt() {
        val player = Soldier(Team.PLAYER, 600f, 600f)
        val enemy = Soldier(Team.ENEMY, 800f, 600f)
        val battle = emptyBattle(player, enemy)
        enemy.patrol.clear()
        repeat(10) { battle.update(dt) }
        assertEquals(enemy, player.firingAt)
        assertEquals(player, enemy.firingAt)

        // A wall between them: no line of fire either way.
        val hidden = Soldier(Team.PLAYER, Terrain.center(8), Terrain.center(20))
        val other = Soldier(Team.ENEMY, Terrain.center(12), Terrain.center(20))
        val walled = emptyBattle(hidden, other)
        for (r in 0 until Terrain.ROWS - 1) for (c in 9..11) walled.terrain.addObstacle(ObstacleKind.ROCK, c, r)
        other.patrol.clear()
        repeat(10) { walled.update(dt) }
        assertEquals(null, hidden.firingAt)
        assertEquals(null, other.firingAt)
    }

    @Test
    fun fieldOfFireStopsAtObstaclesAndAgreesWithLineOfSight() {
        val terrain = Terrain()
        for (r in 5..15) terrain.addObstacle(ObstacleKind.ROCK, 12, r)
        val origin = tileCenter(8, 10)
        val reach = FloatArray(361)
        terrain.fieldOfFire(origin, 470f, Battle.PEEK_DISTANCE, 0f, 360f, reach)

        // East (index 0) runs into the wall; west (index 180) is open to full range.
        assertTrue(reach[0] < 12 * Terrain.TILE - origin.x + 8f, "east reach ${reach[0]}")
        assertEquals(470f, reach[180])

        // Points the field marks as reachable really are in line of sight, and points past the wall aren't.
        for (i in reach.indices step 15) {
            val angle = i * MathUtils.degRad
            val inside = Vector2(origin).add(MathUtils.cos(angle) * (reach[i] - 8f), MathUtils.sin(angle) * (reach[i] - 8f))
            assertTrue(terrain.lineOfSight(origin.x, origin.y, inside.x, inside.y, Battle.PEEK_DISTANCE), "ray $i")
        }
        val behindWall = tileCenter(14, 10)
        assertFalse(terrain.lineOfSight(origin.x, origin.y, behindWall.x, behindWall.y, Battle.PEEK_DISTANCE))
    }

    /** A soldier walks past a stationary robot to a point beyond it; returns (fired on the way, arrived). */
    private fun walkPastEnemy(attackMove: Boolean): Pair<Boolean, Boolean> {
        val soldier = Soldier(Team.PLAYER, 300f, 600f)
        soldier.hp = 10_000f
        val enemy = Soldier(Team.ENEMY, 700f, 800f)
        enemy.hp = 10_000f
        enemy.patrol.clear()
        val battle = emptyBattle(soldier, enemy)
        val dest = Vector2(1100f, 600f)
        battle.orderMove(soldier, dest, attackMove)
        var firedWhileMoving = false
        repeat(60 * 15) {
            battle.update(dt)
            if (soldier.moveTarget != null && soldier.firingAt != null) firedWhileMoving = true
        }
        return firedWhileMoving to (soldier.moveTarget == null && soldier.pos.dst(dest) < 30f)
    }

    @Test
    fun attackMoveShootsOnTheWayAndStillArrives() {
        val (fired, arrived) = walkPastEnemy(attackMove = true)
        assertTrue(fired, "should fire at the enemy while moving")
        assertTrue(arrived, "should keep going to the destination")
    }

    @Test
    fun runningIgnoresEnemiesUntilArrival() {
        val (fired, arrived) = walkPastEnemy(attackMove = false)
        assertFalse(fired, "a run order shouldn't stop to shoot")
        assertTrue(arrived)
    }

    @Test
    fun soldiersOnlyNoticeEnemiesInFrontOfThem() {
        val watcher = Soldier(Team.PLAYER, 600f, 600f)
        watcher.angle = 0f
        val ahead = Soldier(Team.ENEMY, 800f, 650f)
        val behind = Soldier(Team.ENEMY, 400f, 600f)
        val battle = emptyBattle(watcher, ahead, behind)
        assertTrue(battle.canSee(watcher, ahead))
        assertFalse(battle.canSee(watcher, behind))
        assertTrue(battle.hasLineOfSight(watcher, behind), "nothing in the way, just facing the wrong way")

        // Right behind your back is close enough to notice.
        assertTrue(battle.canSee(watcher, Soldier(Team.ENEMY, 560f, 600f)))

        // Snipers have a narrow cone: 50 degrees off-axis is outside it, but inside a rifleman's.
        val offAxis = Soldier(Team.ENEMY, 600f + 200f * MathUtils.cosDeg(50f), 600f + 200f * MathUtils.sinDeg(50f))
        val sniper = Soldier(Team.PLAYER, 600f, 600f, SoldierType.SNIPER).apply { angle = 0f }
        assertTrue(battle.canSee(watcher, offAxis))
        assertFalse(battle.canSee(sniper, offAxis))
    }

    @Test
    fun anUnawareSentryCanBeFlankedButTurnsWhenShot() {
        // The robot faces away (east); the player approaches from the west and opens fire.
        val robot = Soldier(Team.ENEMY, 900f, 600f)
        robot.angle = 0f
        robot.hp = 10_000f
        val player = Soldier(Team.PLAYER, 700f, 600f)
        player.angle = 0f
        player.hp = 10_000f
        val battle = emptyBattle(robot, player)
        battle.update(dt)
        assertEquals(null, robot.aggroTarget, "robot shouldn't see a soldier behind it")
        assertEquals(robot, player.firingAt)

        repeat(60 * 2) { battle.update(dt) }
        assertEquals(player, robot.aggroTarget, "getting shot should alert the robot")
        assertTrue(abs(Soldier.angleBetween(robot.angle, 180f)) < 15f, "robot should turn to face the shooter")
    }

    @Test
    fun idleSoldiersTurnTowardWhoeverShootsThem() {
        val player = Soldier(Team.PLAYER, 600f, 600f)
        player.angle = 0f
        player.hp = 10_000f
        val robot = Soldier(Team.ENEMY, 400f, 600f)
        robot.angle = 0f
        robot.hp = 10_000f
        val battle = emptyBattle(player, robot)
        robot.aggroTarget = player
        repeat(60 * 3) { battle.update(dt) }
        assertEquals(robot, player.firingAt, "the shot-at soldier should turn and return fire")
    }

    @Test
    fun robotsTakeCoverAndCallTheirSquad() {
        // A rock between the robots and the player; robots start out in the open beside it.
        val player = Soldier(Team.PLAYER, Terrain.center(6), Terrain.center(10))
        player.angle = 0f
        player.hp = 10_000f
        val robot = Soldier(Team.ENEMY, Terrain.center(10), Terrain.center(13))
        robot.angle = 180f
        robot.hp = 10_000f
        val squadmate = Soldier(Team.ENEMY, Terrain.center(13), Terrain.center(13))
        squadmate.angle = 0f
        squadmate.hp = 10_000f
        val battle = emptyBattle(player, robot, squadmate)
        for (r in 9..11) battle.terrain.addObstacle(ObstacleKind.ROCK, 9, r)

        repeat(60 * 4) { battle.update(dt) }
        assertEquals(player, squadmate.aggroTarget, "the robot that spots the player should call its squadmate")
        assertTrue(battle.terrain.inCover(robot.pos, player.pos), "robot at ${robot.pos} should be behind the rock")
        assertTrue(battle.canTarget(robot, player), "robot should still have a shot past the rock")
        // The rock has just one spot that gives cover and a shot, so the squadmate can't share it.
        assertEquals(null, squadmate.coverSpot)
        assertTrue(player.hp < 10_000f, "robots should be shooting from cover")
    }
}
