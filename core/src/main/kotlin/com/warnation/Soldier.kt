package com.warnation

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import kotlin.math.abs

enum class Team { PLAYER, ENEMY }

enum class Weapon { RIFLE, SNIPER_RIFLE, GRENADE, ROCKET }

/**
 * Stats for each kind of soldier, as fielded by the player; robots get weaker versions.
 * [fieldOfView] is the width in degrees of the cone in front of the soldier where it spots enemies.
 * [splashRadius] > 0 means the shot explodes. [indirect] soldiers lob shots over obstacles at
 * targets any squadmate can see. [gunForward]/[gunSide] place the muzzle relative to the body centre.
 * [spotRange] is how far away a soldier can spot targets for its squad's grenadiers.
 */
enum class SoldierType(
    val displayName: String,
    val role: String,
    val maxHp: Float,
    val speed: Float,
    val range: Float,
    val damage: Float,
    val fireInterval: Float,
    val weapon: Weapon,
    val splashRadius: Float = 0f,
    val coverBlockChance: Float = 0.6f,
    val indirect: Boolean = false,
    val gunForward: Float = 30f,
    val gunSide: Float = -9.5f,
    val badge: String = "",
    val fieldOfView: Float = 120f,
    val spotRange: Float = Battle.AGGRO_RADIUS,
) {
    RIFLEMAN(
        "Rifleman", "Steady all-rounder that holds the line",
        maxHp = 100f, speed = 130f, range = 260f, damage = 9f, fireInterval = 0.45f, weapon = Weapon.RIFLE,
    ),
    SNIPER(
        "Sniper", "Long range, ignores most cover, fragile",
        maxHp = 70f, speed = 120f, range = 470f, damage = 40f, fireInterval = 2.2f, weapon = Weapon.SNIPER_RIFLE,
        coverBlockChance = 0.3f, gunForward = 35f, badge = "S", fieldOfView = 70f,
    ),
    GRENADIER(
        "Grenadier", "Lobs grenades over walls at spotted foes",
        maxHp = 90f, speed = 125f, range = 300f, damage = 28f, fireInterval = 2.4f, weapon = Weapon.GRENADE,
        splashRadius = 70f, coverBlockChance = 0f, indirect = true, gunForward = 16f, gunSide = 0f, badge = "G",
    ),
    ROCKETEER(
        "Rocketeer", "Slow rockets with a big blast",
        maxHp = 110f, speed = 110f, range = 340f, damage = 50f, fireInterval = 3.5f, weapon = Weapon.ROCKET,
        splashRadius = 55f, gunForward = 34f, badge = "R", fieldOfView = 100f,
    ),
    SCOUT(
        "Scout", "Fast, sees wide and spots far for grenadiers",
        maxHp = 65f, speed = 175f, range = 220f, damage = 7f, fireInterval = 0.35f, weapon = Weapon.RIFLE,
        badge = "Sc", fieldOfView = 170f, spotRange = 520f,
    ),
}

class Soldier(val team: Team, x: Float, y: Float, val type: SoldierType = SoldierType.RIFLEMAN) {
    val pos = Vector2(x, y)
    var angle = if (team == Team.PLAYER) 45f else 225f

    private val enemy = team == Team.ENEMY
    val maxHp = type.maxHp * if (enemy) 0.85f else 1f
    var hp = maxHp
    val speed = type.speed * if (enemy) 0.85f else 1f
    val range = type.range
    val damage = type.damage * if (enemy) 0.95f else 1f
    val fireInterval = type.fireInterval * if (enemy) 1.1f else 1f
    var cooldown = MathUtils.random(fireInterval)
    var muzzleFlash = 0f

    /** The enemy this soldier is shooting at this frame, if any (drawn as its line of fire). */
    var firingAt: Soldier? = null

    /** Whoever last shot at this soldier, and for how much longer it keeps turning to look for them. */
    var threat: Soldier? = null
    var threatTime = 0f

    /** Where an idle robot is turning to look while it pauses on patrol. */
    var scanAngle = angle

    // Orders given by the player. On an attack-move the soldier shoots at enemies on the way;
    // otherwise it ignores them until it arrives.
    var moveTarget: Vector2? = null
    var attackMove = false
    var attackTarget: Soldier? = null

    /** Where an idle soldier stands; it walks back here if shoved away. */
    var holdPosition: Vector2? = null

    // Enemy AI state.
    val patrol = mutableListOf<Vector2>()
    var patrolIndex = 0
    var patrolWait = 0f
    var aggroTarget: Soldier? = null

    /** Where a fighting robot is taking cover from its target, and when it next checks that spot still works. */
    var coverSpot: Vector2? = null
    var coverTimer = 0f

    // Navigation: remaining waypoints toward pathGoal, recomputed when the goal moves.
    val path = mutableListOf<Vector2>()
    var pathGoal: Vector2? = null
    var repathTimer = 0f
    val progressCheckPos = Vector2(x, y)
    var progressTimer = 0f

    val alive get() = hp > 0f

    fun moveTo(target: Vector2, attackMove: Boolean = false) {
        moveTarget = target.cpy()
        this.attackMove = attackMove
        attackTarget = null
        holdPosition = null
        clearPath()
    }

    fun attack(target: Soldier) {
        attackTarget = target
        moveTarget = null
        holdPosition = null
        clearPath()
    }

    fun clearPath() {
        path.clear()
        pathGoal = null
    }

    fun distanceTo(other: Soldier) = pos.dst(other.pos)

    /** True if (x, y) is inside the vision cone, or close enough to notice regardless of facing. */
    fun inView(x: Float, y: Float): Boolean {
        if (pos.dst(x, y) <= CLOSE_AWARENESS) return true
        val toward = MathUtils.atan2(y - pos.y, x - pos.x) * MathUtils.radDeg
        return abs(angleBetween(angle, toward)) <= type.fieldOfView / 2f
    }

    /** World position of the gun barrel, used for bullets and muzzle flash. */
    fun gunTip(out: Vector2): Vector2 = out.set(type.gunForward, type.gunSide).rotateDeg(angle).add(pos)

    companion object {
        const val RADIUS = 21f

        /** Enemies this close are noticed even behind a soldier's back. */
        const val CLOSE_AWARENESS = 60f

        /** Signed smallest rotation in degrees from [from] to [to], in -180..180. */
        fun angleBetween(from: Float, to: Float) = ((to - from) % 360f + 540f) % 360f - 180f
    }
}
