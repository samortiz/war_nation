package com.warnation

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Outcome { PLAYING, VICTORY, DEFEAT }

/**
 * A shot in flight. Rounds with no [dest] home in on [target], or fly to [blockedAt] when cover stops them.
 * Grenades and rockets fly to the fixed point [dest] and explode, so a moving target can dodge them.
 */
class Projectile(
    val weapon: Weapon,
    val shooter: Soldier,
    val target: Soldier,
    val damage: Float,
    val splashRadius: Float,
    from: Vector2,
    val dest: Vector2?,
    val blockedAt: Vector2?,
) {
    val start = Vector2(from)
    val pos = Vector2(from)
    val dir = Vector2()
    var done = false

    /** How far along its flight a grenade is, 0..1, for drawing the arc. */
    val progress get() = if (dest == null) 1f else min(1f, start.dst(pos) / max(1f, start.dst(dest)))
}

class Corpse(val x: Float, val y: Float, val angle: Float, val team: Team, val type: SoldierType) {
    var age = 0f
}

/** A blast, which then lingers as a scorch mark. */
class Explosion(val x: Float, val y: Float, val radius: Float) {
    var age = 0f
}

/** Ricochet effect where a bullet hit cover. */
class Spark(val x: Float, val y: Float) {
    var age = 0f
}

/** Game state and simulation, independent of rendering and input. */
class Battle(
    withObstacles: Boolean = true,
    private val playerSquad: List<SoldierType> = Squad.DEFAULT.flatMap { (type, count) -> List(count) { type } },
    private val enemySquads: List<EnemySquad> = SKIRMISH,
    /** Generates the same map every time; null for a random one. */
    mapSeed: Long? = null,
) {
    val terrain = Terrain()
    val soldiers = mutableListOf<Soldier>()
    val projectiles = mutableListOf<Projectile>()
    val corpses = mutableListOf<Corpse>()
    val sparks = mutableListOf<Spark>()
    val explosions = mutableListOf<Explosion>()
    var outcome = Outcome.PLAYING
        private set

    val playerSpawn = Vector2(380f, 380f)

    private val tmp = Vector2()

    init {
        if (withObstacles) {
            mapSeed?.let { MathUtils.random.setSeed(it) }
            terrain.generate(listOf(playerSpawn to 280f) + enemySquads.map { it.post to 170f })
            // Only the map is fixed; the fight itself stays unpredictable.
            if (mapSeed != null) MathUtils.random.setSeed(System.nanoTime())
        }
        spawnPlayers()
        spawnEnemies()
    }

    fun count(team: Team) = soldiers.count { it.team == team }

    fun soldierAt(x: Float, y: Float, radius: Float): Soldier? =
        soldiers.filter { it.pos.dst(x, y) <= radius }.minByOrNull { it.pos.dst(x, y) }

    /**
     * Orders a move, redirecting taps on an obstacle to the nearest open ground. With [attackMove] the
     * soldier fires at enemies it can hit along the way.
     */
    fun orderMove(s: Soldier, target: Vector2, attackMove: Boolean = false) =
        s.moveTo(terrain.nearestStandable(target.x, target.y, Soldier.RADIUS), attackMove)

    /** Sends a group to a compact formation around [target], giving each soldier the nearest free spot. */
    fun orderGroupMove(group: List<Soldier>, target: Vector2, attackMove: Boolean = false) {
        if (group.size == 1) return orderMove(group[0], target, attackMove)
        val slots = formationSlots(terrain.nearestStandable(target.x, target.y, Soldier.RADIUS), group.size).toMutableList()
        val unassigned = group.toMutableList()
        while (unassigned.isNotEmpty()) {
            var bestSoldier = unassigned[0]
            var bestSlot = slots[0]
            for (s in unassigned) for (slot in slots) {
                if (s.pos.dst2(slot) < bestSoldier.pos.dst2(bestSlot)) {
                    bestSoldier = s
                    bestSlot = slot
                }
            }
            bestSoldier.moveTo(bestSlot, attackMove)
            unassigned.remove(bestSoldier)
            slots.remove(bestSlot)
        }
    }

    fun orderAttack(s: Soldier, target: Soldier) = s.attack(target)

    /** Open spots around [center], nearest first, that a soldier fits in and can walk to straight from the centre. */
    private fun formationSlots(center: Vector2, count: Int): List<Vector2> {
        val candidates = mutableListOf<Vector2>()
        for (i in -FORMATION_REACH..FORMATION_REACH) for (j in -FORMATION_REACH..FORMATION_REACH) {
            candidates.add(Vector2(center.x + i * FORMATION_SPACING, center.y + j * FORMATION_SPACING))
        }
        candidates.sortBy { it.dst2(center) }
        val slots = candidates
            .filter { terrain.fits(it, Soldier.RADIUS) && terrain.clearPath(center, it, Soldier.RADIUS) }
            .take(count)
            .toMutableList()
        while (slots.size < count) slots.add(Vector2(center))
        return slots
    }

    /** Nothing blocks a shot between the two, whichever way they face. */
    fun hasLineOfSight(a: Soldier, b: Soldier) = terrain.lineOfSight(a.pos.x, a.pos.y, b.pos.x, b.pos.y, PEEK_DISTANCE)

    /** [a] notices [b]: in line of sight and inside [a]'s forward vision cone (or very close). */
    fun canSee(a: Soldier, b: Soldier) = a.inView(b.pos.x, b.pos.y) && hasLineOfSight(a, b)

    /**
     * In range with a clear shot (or, for indirect fire, spotted by a squadmate). Facing doesn't matter
     * here: a soldier that knows about a target turns to aim before it fires.
     */
    fun canTarget(s: Soldier, target: Soldier): Boolean {
        if (s.distanceTo(target) > s.range) return false
        return hasLineOfSight(s, target) || (s.type.indirect && teamCanSee(s.team, target))
    }

    /** True if [target] is hugging cover against [s]'s shots, so most of them will be stopped. */
    fun shotsBlockedByCover(s: Soldier, target: Soldier) =
        s.type.coverBlockChance > 0f && terrain.inCover(target.pos, s.pos)

    /** Where a shot from [s] stops against the cover [target] is hugging. */
    fun coverPoint(s: Soldier, target: Soldier, out: Vector2): Vector2 =
        out.set(s.pos).sub(target.pos).nor().scl(Soldier.RADIUS + 12f).add(target.pos)

    private fun teamCanSee(team: Team, target: Soldier) =
        soldiers.any { it.team == team && it.alive && it.distanceTo(target) <= it.type.spotRange && canSee(it, target) }

    fun update(dt: Float) {
        for (corpse in corpses) corpse.age += dt
        corpses.removeAll { it.age > CORPSE_LIFETIME }
        for (spark in sparks) spark.age += dt
        sparks.removeAll { it.age > SPARK_LIFETIME }
        for (explosion in explosions) explosion.age += dt
        explosions.removeAll { it.age > SCORCH_LIFETIME }
        if (outcome != Outcome.PLAYING) return

        for (s in soldiers) {
            s.cooldown -= dt
            s.muzzleFlash -= dt
            s.firingAt = null
            s.threatTime -= dt
            if (s.team == Team.PLAYER) updatePlayer(s, dt) else updateEnemy(s, dt)
        }
        separate()
        updateProjectiles(dt)

        for (s in soldiers) if (!s.alive) corpses.add(Corpse(s.pos.x, s.pos.y, s.angle, s.team, s.type))
        soldiers.removeAll { !it.alive }

        outcome = when {
            count(Team.PLAYER) == 0 -> Outcome.DEFEAT
            count(Team.ENEMY) == 0 -> Outcome.VICTORY
            else -> Outcome.PLAYING
        }
    }

    private fun updatePlayer(s: Soldier, dt: Float) {
        if (s.attackTarget?.alive == false) s.attackTarget = null

        s.moveTarget?.let { dest ->
            // Attack-move: keep walking, but aim at and shoot anything in reach, slowing while firing.
            val target = if (s.attackMove) nearestTarget(s, Team.ENEMY, s.range) else null
            if (target != null) aimAndFire(s, target, dt)
            val speed = if (target != null) FIRING_MOVE_SPEED else 1f
            if (navigate(s, dest, dt, ARRIVE_DISTANCE, speed, turn = target == null)) {
                s.moveTarget = null
                s.holdPosition = Vector2(dest)
            }
            return
        }
        val target = s.attackTarget ?: nearestTarget(s, Team.ENEMY, s.range)
        if (target != null) {
            engage(s, target, dt)
            return
        }
        val hold = s.holdPosition ?: Vector2(s.pos).also { s.holdPosition = it }
        if (s.pos.dst(hold) > HOLD_SLACK) {
            navigate(s, hold, dt, ARRIVE_DISTANCE)
            return
        }
        // Under fire from somewhere unseen: turn to look for the shooter.
        val threat = s.threat
        if (threat != null && threat.alive && s.threatTime > 0f) faceToward(s, threat.pos.x, threat.pos.y, dt)
    }

    private fun updateEnemy(s: Soldier, dt: Float) {
        var target = s.aggroTarget
        if (target != null && (!target.alive || s.distanceTo(target) > max(LEASH_RADIUS, s.range + 150f))) target = null
        if (target == null) target = nearestTarget(s, Team.PLAYER, max(AGGRO_RADIUS, s.range))
        if (target !== s.aggroTarget) {
            s.clearPath()
            s.coverSpot = null
            s.coverTimer = 0f
        }
        s.aggroTarget = target

        if (target != null) {
            callSquadmates(s, target)
            fightFromCover(s, target, dt)
            return
        }
        if (s.patrol.isEmpty()) return
        if (s.patrolWait > 0f) {
            s.patrolWait -= dt
            lookAround(s, dt)
            return
        }
        if (navigate(s, s.patrol[s.patrolIndex], dt, ARRIVE_DISTANCE, PATROL_SPEED)) {
            s.patrolIndex = (s.patrolIndex + 1) % s.patrol.size
            s.patrolWait = MathUtils.random(1.5f, 4f)
            s.scanAngle = s.angle
        }
    }

    /** A fighting robot pulls in idle robots nearby to gang up on the same target. */
    private fun callSquadmates(s: Soldier, target: Soldier) {
        for (other in soldiers) {
            if (other.team == s.team && other.aggroTarget == null && other.pos.dst(s.pos) <= ALERT_RADIUS) {
                other.aggroTarget = target
                other.clearPath()
            }
        }
    }

    /**
     * Robots fight from the nearest cover that shields them from [target] while still giving a shot at it,
     * shooting as they run there. Without such cover nearby they close in like anyone else.
     */
    private fun fightFromCover(s: Soldier, target: Soldier, dt: Float) {
        s.coverTimer -= dt
        if (s.coverTimer <= 0f) {
            s.coverTimer = COVER_RECHECK_INTERVAL
            val spot = s.coverSpot
            if (spot == null || !goodCover(s, spot, target)) {
                s.coverSpot = findCover(s, target)
                s.clearPath()
            }
        }
        val spot = s.coverSpot ?: return engage(s, target, dt)
        val shooting = canTarget(s, target)
        if (shooting) aimAndFire(s, target, dt)
        navigate(s, spot, dt, 1f, turn = !shooting)
    }

    private fun findCover(s: Soldier, target: Soldier): Vector2? =
        terrain.coverSpots(s.pos, COVER_SEARCH_RADIUS, Soldier.RADIUS)
            .filter { goodCover(s, it, target) }
            .minByOrNull { it.dst2(s.pos) }

    /** [spot] is shielded from [target], within range of it with a shot past the cover, and not already claimed. */
    fun goodCover(s: Soldier, spot: Vector2, target: Soldier): Boolean {
        if (spot.dst(target.pos) > s.range * COVER_RANGE_MARGIN || !terrain.inCover(spot, target.pos)) return false
        if (!terrain.lineOfSight(spot.x, spot.y, target.pos.x, target.pos.y, PEEK_DISTANCE)) return false
        return soldiers.none { it !== s && it.team == s.team && it.alive && (it.coverSpot?.dst(spot) ?: 1e9f) < Soldier.RADIUS * 2f }
    }

    /** A paused sentry slowly sweeps its gaze left and right. */
    private fun lookAround(s: Soldier, dt: Float) {
        val diff = Soldier.angleBetween(s.angle, s.scanAngle)
        if (abs(diff) < 2f) {
            s.scanAngle = s.angle + MathUtils.random(-SCAN_ARC, SCAN_ARC)
            return
        }
        s.angle += MathUtils.clamp(diff, -SCAN_SPEED * dt, SCAN_SPEED * dt)
    }

    /** Get within range and line of sight, turn toward the target and shoot. */
    private fun engage(s: Soldier, target: Soldier, dt: Float) {
        if (!canTarget(s, target)) {
            navigate(s, target.pos, dt, 0f)
            return
        }
        s.clearPath()
        aimAndFire(s, target, dt)
    }

    /** Turns toward a target that can be hit and fires once on target and reloaded. */
    private fun aimAndFire(s: Soldier, target: Soldier, dt: Float) {
        s.firingAt = target
        val aimError = faceToward(s, target.pos.x, target.pos.y, dt)
        if (aimError < AIM_TOLERANCE && s.cooldown <= 0f) fire(s, target)
    }

    private fun fire(s: Soldier, target: Soldier) {
        s.cooldown = s.fireInterval
        if (s.type.weapon != Weapon.GRENADE) s.muzzleFlash = MUZZLE_FLASH_TIME
        val type = s.type
        val blocked = type.coverBlockChance > 0f && terrain.inCover(target.pos, s.pos) &&
            MathUtils.random() < type.coverBlockChance
        val blockedAt = if (blocked) coverPoint(s, target, Vector2()) else null
        val dest = when (type.weapon) {
            Weapon.GRENADE -> Vector2(target.pos).add(
                MathUtils.random(-GRENADE_SPREAD, GRENADE_SPREAD),
                MathUtils.random(-GRENADE_SPREAD, GRENADE_SPREAD),
            )
            Weapon.ROCKET -> blockedAt ?: Vector2(target.pos)
            else -> null
        }
        projectiles.add(
            Projectile(
                type.weapon, s, target, s.damage, type.splashRadius, s.gunTip(Vector2()), dest,
                if (dest == null) blockedAt else null,
            ),
        )
    }

    /**
     * Walks toward [dest] around obstacles, recomputing the path when the destination moves.
     * Returns true once within [arriveDistance]. With [turn] false the soldier keeps its facing (to aim).
     */
    private fun navigate(
        s: Soldier,
        dest: Vector2,
        dt: Float,
        arriveDistance: Float,
        speedScale: Float = 1f,
        turn: Boolean = true,
    ): Boolean {
        if (s.pos.dst(dest) <= arriveDistance) {
            s.clearPath()
            return true
        }
        s.repathTimer -= dt
        s.progressTimer += dt
        if (s.progressTimer >= STUCK_CHECK_INTERVAL) {
            // Barely moved: likely shoved off the route into a corner, so plan a fresh path.
            if (s.pos.dst(s.progressCheckPos) < s.speed * speedScale * STUCK_CHECK_INTERVAL * 0.25f) s.pathGoal = null
            s.progressCheckPos.set(s.pos)
            s.progressTimer = 0f
        }
        val goal = s.pathGoal
        if (goal == null || (s.repathTimer <= 0f && goal.dst(dest) > REPATH_DISTANCE)) {
            s.pathGoal = Vector2(dest)
            s.repathTimer = REPATH_INTERVAL
            s.path.clear()
            terrain.findPath(s.pos, dest, Soldier.RADIUS)?.let { s.path.addAll(it) }
        }
        // Follow intermediate waypoints; the final leg heads for the live destination.
        if (s.path.size > 1) {
            if (moveToward(s, s.path[0], dt, WAYPOINT_DISTANCE, speedScale, turn)) s.path.removeAt(0)
            return false
        }
        val arrived = moveToward(s, dest, dt, arriveDistance, speedScale, turn)
        if (arrived) s.clearPath()
        return arrived
    }

    /** Returns true once the soldier is within [arriveDistance] of [dest]. */
    private fun moveToward(
        s: Soldier,
        dest: Vector2,
        dt: Float,
        arriveDistance: Float,
        speedScale: Float = 1f,
        turn: Boolean = true,
    ): Boolean {
        val dx = dest.x - s.pos.x
        val dy = dest.y - s.pos.y
        val d = sqrt(dx * dx + dy * dy)
        if (d <= arriveDistance || d < 0.001f) return true
        if (turn) faceToward(s, dest.x, dest.y, dt)
        val step = min(d, s.speed * speedScale * dt)
        s.pos.add(dx / d * step, dy / d * step)
        return d - step <= arriveDistance
    }

    /** Rotates toward a point at a limited turn rate; returns the remaining angle in degrees. */
    private fun faceToward(s: Soldier, x: Float, y: Float, dt: Float): Float {
        val desired = MathUtils.atan2(y - s.pos.y, x - s.pos.x) * MathUtils.radDeg
        val diff = ((desired - s.angle + 540f) % 360f) - 180f
        val turn = TURN_SPEED * dt
        s.angle += MathUtils.clamp(diff, -turn, turn)
        return abs(diff) - min(abs(diff), turn)
    }

    private fun nearestTarget(s: Soldier, team: Team, maxDistance: Float): Soldier? =
        soldiers.filter { it.team == team && it.alive && s.distanceTo(it) <= maxDistance }
            .sortedBy { s.distanceTo(it) }
            .firstOrNull { canSee(s, it) || (s.type.indirect && s.distanceTo(it) <= s.range && teamCanSee(s.team, it)) }

    /** Pushes overlapping soldiers apart and out of obstacles, keeping everyone inside the map. */
    private fun separate() {
        val minDist = Soldier.RADIUS * 2f
        for (i in soldiers.indices) {
            val a = soldiers[i]
            for (j in i + 1 until soldiers.size) {
                val b = soldiers[j]
                tmp.set(b.pos).sub(a.pos)
                val d = tmp.len()
                if (d >= minDist) continue
                if (d < 0.01f) tmp.set(MathUtils.random(-1f, 1f), MathUtils.random(-1f, 1f))
                tmp.nor().scl((minDist - d) / 2f)
                a.pos.sub(tmp)
                b.pos.add(tmp)
            }
        }
        for (s in soldiers) {
            terrain.pushOut(s.pos, Soldier.RADIUS)
            s.pos.x = MathUtils.clamp(s.pos.x, Soldier.RADIUS, Terrain.WIDTH - Soldier.RADIUS)
            s.pos.y = MathUtils.clamp(s.pos.y, Soldier.RADIUS, Terrain.HEIGHT - Soldier.RADIUS)
        }
    }

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            val explosive = p.dest != null
            if (!explosive && p.blockedAt == null && !p.target.alive) {
                p.done = true
                continue
            }
            val dest = p.dest ?: p.blockedAt ?: p.target.pos
            p.dir.set(dest).sub(p.pos)
            val d = p.dir.len()
            val step = projectileSpeed(p.weapon) * dt
            val reach = if (explosive || p.blockedAt != null) 0f else Soldier.RADIUS * 0.5f
            if (d > 0.001f) p.dir.scl(1f / d)
            if (d <= step + reach) {
                p.pos.set(dest)
                when {
                    explosive -> explode(p)
                    p.blockedAt != null -> sparks.add(Spark(dest.x, dest.y))
                    else -> p.target.hp -= p.damage
                }
                noticeShooter(p.target, p.shooter)
                alert(p.shooter, dest)
                p.done = true
            } else {
                p.pos.mulAdd(p.dir, step)
                if (p.weapon == Weapon.ROCKET && rocketStruck(p)) {
                    explode(p)
                    alert(p.shooter, p.pos)
                    p.done = true
                }
            }
        }
        projectiles.removeAll { it.done }
    }

    /** Rockets fly straight and detonate early on an obstacle or any enemy in their way. */
    private fun rocketStruck(p: Projectile): Boolean {
        val clearOfEnds = p.start.dst(p.pos) > PEEK_DISTANCE && p.pos.dst(p.dest!!) > PEEK_DISTANCE
        if (clearOfEnds && terrain.isBlockedAt(p.pos.x, p.pos.y)) return true
        return soldiers.any { it.team != p.shooter.team && it.alive && it.pos.dst(p.pos) <= Soldier.RADIUS }
    }

    /** Damages every enemy of the shooter in the blast, less toward the edge. Obstacles don't shield it. */
    private fun explode(p: Projectile) {
        explosions.add(Explosion(p.pos.x, p.pos.y, p.splashRadius))
        for (s in soldiers) {
            if (s.team == p.shooter.team || !s.alive) continue
            val edge = max(0f, s.pos.dst(p.pos) - Soldier.RADIUS)
            if (edge <= p.splashRadius) {
                s.hp -= p.damage * (1f - 0.5f * edge / p.splashRadius)
                noticeShooter(s, p.shooter)
            }
        }
    }

    /** A soldier that's shot at turns to look for the shooter for a while. */
    private fun noticeShooter(victim: Soldier, shooter: Soldier) {
        victim.threat = shooter
        victim.threatTime = THREAT_MEMORY
    }

    /** Being shot at, hit or not, alerts enemies near the impact to the shooter. */
    private fun alert(shooter: Soldier, at: Vector2) {
        if (shooter.team != Team.PLAYER || !shooter.alive) return
        for (s in soldiers) {
            if (s.team == Team.ENEMY && s.aggroTarget == null && s.pos.dst(at) <= ALERT_RADIUS) {
                s.aggroTarget = shooter
                s.clearPath()
            }
        }
    }

    private fun spawnPlayers() {
        for ((i, type) in playerSquad.withIndex()) {
            val x = playerSpawn.x + (i % 3 - 1) * 70f
            val y = playerSpawn.y + (i / 3 - 0.5f) * 70f
            soldiers.add(Soldier(Team.PLAYER, x, y, type))
        }
    }

    private fun spawnEnemies() {
        // Each squad patrols a loop of waypoints around its post.
        for (squad in enemySquads) {
            val post = squad.post
            val waypoints = (0 until 3).map {
                val a = it * 120f + MathUtils.random(-30f, 30f)
                Vector2(post.x + MathUtils.cosDeg(a) * 260f, post.y + MathUtils.sinDeg(a) * 260f)
            }
            for ((i, type) in squad.members.withIndex()) {
                val s = Soldier(Team.ENEMY, post.x + i * 50f, post.y + MathUtils.random(-30f, 30f), type)
                waypoints.forEach { w -> s.patrol.add(terrain.nearestOpen(w.x + i * 45f, w.y)) }
                s.patrolIndex = MathUtils.random(waypoints.size - 1)
                soldiers.add(s)
            }
        }
    }

    companion object {
        const val AGGRO_RADIUS = 330f
        const val LEASH_RADIUS = 650f
        const val ALERT_RADIUS = 380f
        const val COVER_SEARCH_RADIUS = 260f
        const val COVER_RECHECK_INTERVAL = 0.75f
        const val COVER_RANGE_MARGIN = 0.92f
        const val ARRIVE_DISTANCE = 6f
        const val WAYPOINT_DISTANCE = 10f
        const val HOLD_SLACK = 14f
        const val REPATH_DISTANCE = 48f
        const val REPATH_INTERVAL = 0.5f
        const val STUCK_CHECK_INTERVAL = 0.5f
        const val PATROL_SPEED = 0.5f
        const val FIRING_MOVE_SPEED = 0.7f
        const val SCAN_ARC = 100f
        const val SCAN_SPEED = 70f
        const val THREAT_MEMORY = 3f
        const val TURN_SPEED = 540f
        const val AIM_TOLERANCE = 12f
        const val PEEK_DISTANCE = 36f
        const val FORMATION_SPACING = 52f
        const val FORMATION_REACH = 4
        const val GRENADE_SPREAD = 18f
        const val MUZZLE_FLASH_TIME = 0.06f
        const val CORPSE_LIFETIME = 6f
        const val SPARK_LIFETIME = 0.25f
        const val BLAST_TIME = 0.35f
        const val SCORCH_LIFETIME = 8f

        /** The robot garrison for a quick battle outside the campaign. */
        val SKIRMISH = listOf(
            EnemySquad(1250f, 1050f, SoldierType.RIFLEMAN, SoldierType.SNIPER),
            EnemySquad(2150f, 650f, SoldierType.RIFLEMAN, SoldierType.RIFLEMAN, SoldierType.GRENADIER),
            EnemySquad(1000f, 1600f, SoldierType.RIFLEMAN, SoldierType.ROCKETEER),
            EnemySquad(2150f, 1550f, SoldierType.RIFLEMAN, SoldierType.SNIPER, SoldierType.ROCKETEER),
        )

        fun projectileSpeed(weapon: Weapon) = when (weapon) {
            Weapon.RIFLE -> 1100f
            Weapon.SNIPER_RIFLE -> 2600f
            Weapon.GRENADE -> 420f
            Weapon.ROCKET -> 520f
        }
    }
}
