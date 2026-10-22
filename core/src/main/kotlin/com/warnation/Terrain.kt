package com.warnation

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ObstacleKind { ROCK, CRATE, TREE }

class Obstacle(val kind: ObstacleKind, val col: Int, val row: Int, val variant: Int, val rotation: Float)

class Decoration(val kind: Int, val x: Float, val y: Float, val rotation: Float)

/** The battlefield grid: ground tiles, obstacles that block movement and sight, and pathfinding over them. */
class Terrain {
    /** Ground tile index per cell: 0..1 grass, 2..3 dirt. */
    val ground = Array(COLS) { IntArray(ROWS) }
    val obstacles = mutableListOf<Obstacle>()
    val decorations = mutableListOf<Decoration>()
    private val blocked = Array(COLS) { BooleanArray(ROWS) }

    fun isBlocked(c: Int, r: Int) = c < 0 || r < 0 || c >= COLS || r >= ROWS || blocked[c][r]

    fun isBlockedAt(x: Float, y: Float) = isBlocked(col(x), row(y))

    fun addObstacle(kind: ObstacleKind, c: Int, r: Int) {
        if (isBlocked(c, r)) return
        blocked[c][r] = true
        val rotation = when (kind) {
            ObstacleKind.CRATE -> MathUtils.random(3) * 90f + MathUtils.random(-6f, 6f)
            else -> MathUtils.random(360f)
        }
        obstacles.add(Obstacle(kind, c, r, MathUtils.random(1), rotation))
    }

    /**
     * True if nothing blocks the straight line between two points. Obstacles within [peek] of either
     * end are ignored, so soldiers hugging cover can lean out and shoot past it.
     */
    fun lineOfSight(x0: Float, y0: Float, x1: Float, y1: Float, peek: Float = 0f): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        val steps = max(1, (length / SAMPLE_STEP).toInt())
        for (i in 0..steps) {
            val along = length * i / steps
            if (along < peek || length - along < peek) continue
            if (isBlockedAt(x0 + dx * i / steps, y0 + dy * i / steps)) return false
        }
        return true
    }

    /**
     * The area a shooter at [origin] can see and hit, across the sector from [fromDegrees] sweeping
     * [sweepDegrees]: for each of [reach].size evenly spaced directions (both edges included), how far a shot
     * travels before an obstacle stops it, capped at [range]. Obstacles within [peek] of the origin don't
     * count, matching [lineOfSight] for a soldier leaning past cover it's hugging.
     */
    fun fieldOfFire(origin: Vector2, range: Float, peek: Float, fromDegrees: Float, sweepDegrees: Float, reach: FloatArray) {
        for (i in reach.indices) {
            val angle = (fromDegrees + sweepDegrees * i / (reach.size - 1)) * MathUtils.degRad
            val dx = MathUtils.cos(angle)
            val dy = MathUtils.sin(angle)
            var distance = FIELD_STEP
            var stop = range
            while (distance < range) {
                if (distance >= peek && isBlockedAt(origin.x + dx * distance, origin.y + dy * distance)) {
                    stop = distance
                    break
                }
                distance += FIELD_STEP
            }
            reach[i] = stop
        }
    }

    /** True if a body of [radius] can walk straight from [a] to [b] without touching an obstacle. */
    fun clearPath(a: Vector2, b: Vector2, radius: Float): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) return !isBlockedAt(a.x, a.y)
        val nx = -dy / length * radius
        val ny = dx / length * radius
        return lineOfSight(a.x, a.y, b.x, b.y) &&
            lineOfSight(a.x + nx, a.y + ny, b.x + nx, b.y + ny) &&
            lineOfSight(a.x - nx, a.y - ny, b.x - nx, b.y - ny)
    }

    /** True if a soldier at [target] is hugging an obstacle that sits between it and [shooter]. */
    fun inCover(target: Vector2, shooter: Vector2): Boolean {
        val toShooterX = shooter.x - target.x
        val toShooterY = shooter.y - target.y
        val shooterDist = sqrt(toShooterX * toShooterX + toShooterY * toShooterY)
        if (shooterDist < COVER_MIN_RANGE) return false
        val c0 = col(target.x)
        val r0 = row(target.y)
        for (c in c0 - 1..c0 + 1) for (r in r0 - 1..r0 + 1) {
            if (!inBounds(c, r) || !blocked[c][r]) continue
            val nearX = MathUtils.clamp(target.x, c * TILE, (c + 1) * TILE)
            val nearY = MathUtils.clamp(target.y, r * TILE, (r + 1) * TILE)
            if (Vector2.dst(target.x, target.y, nearX, nearY) > COVER_DISTANCE) continue
            val toCoverX = center(c) - target.x
            val toCoverY = center(r) - target.y
            val coverDist = sqrt(toCoverX * toCoverX + toCoverY * toCoverY)
            val dot = (toCoverX * toShooterX + toCoverY * toShooterY) / (coverDist * shooterDist)
            if (dot > COVER_MIN_DOT) return true
        }
        return false
    }

    /**
     * Open spots within [within] of [near] where a body of [radius] stands hugging a face of an obstacle:
     * the middle of each exposed face and both ends, where a soldier can peek around the corner.
     */
    fun coverSpots(near: Vector2, within: Float, radius: Float): List<Vector2> {
        val spots = mutableListOf<Vector2>()
        val reach = MathUtils.ceil(within / TILE)
        val c0 = col(near.x)
        val r0 = row(near.y)
        val offset = TILE / 2f + radius + 3f
        for (c in c0 - reach..c0 + reach) for (r in r0 - reach..r0 + reach) {
            if (!inBounds(c, r) || !blocked[c][r]) continue
            for ((dc, dr) in ORTHOGONAL) {
                if (isBlocked(c + dc, r + dr)) continue
                for (along in -1..1) {
                    // Slide along the face: perpendicular to the (dc, dr) direction.
                    val slide = along * TILE / 2f
                    val spot = Vector2(center(c) + dc * offset - dr * slide, center(r) + dr * offset + dc * slide)
                    if (spot.dst(near) <= within && fits(spot, radius)) spots.add(spot)
                }
            }
        }
        return spots
    }

    /** True if a circle at [pos] is inside the map and touches no obstacle. */
    fun fits(pos: Vector2, radius: Float): Boolean {
        if (pos.x < radius || pos.y < radius || pos.x > WIDTH - radius || pos.y > HEIGHT - radius) return false
        if (isBlockedAt(pos.x, pos.y)) return false
        val pushed = Vector2(pos)
        pushOut(pushed, radius)
        return pushed.epsilonEquals(pos, 0.01f)
    }

    /** Pushes a circle out of any obstacle tiles it overlaps. */
    fun pushOut(pos: Vector2, radius: Float) {
        val c0 = col(pos.x)
        val r0 = row(pos.y)
        for (c in c0 - 1..c0 + 1) for (r in r0 - 1..r0 + 1) {
            if (!inBounds(c, r) || !blocked[c][r]) continue
            val minX = c * TILE
            val minY = r * TILE
            val maxX = minX + TILE
            val maxY = minY + TILE
            val dx = pos.x - MathUtils.clamp(pos.x, minX, maxX)
            val dy = pos.y - MathUtils.clamp(pos.y, minY, maxY)
            val d2 = dx * dx + dy * dy
            if (d2 >= radius * radius) continue
            if (d2 > 0.0001f) {
                val d = sqrt(d2)
                pos.add(dx / d * (radius - d), dy / d * (radius - d))
            } else {
                // Centre is inside the tile: leave through the nearest edge.
                val left = pos.x - minX
                val right = maxX - pos.x
                val down = pos.y - minY
                val up = maxY - pos.y
                when (minOf(left, right, down, up)) {
                    left -> pos.x = minX - radius
                    right -> pos.x = maxX + radius
                    down -> pos.y = minY - radius
                    else -> pos.y = maxY + radius
                }
            }
        }
    }

    /** Centre of the open tile closest to (x, y). */
    fun nearestOpen(x: Float, y: Float): Vector2 {
        val start = MathUtils.clamp(col(x), 0, COLS - 1) to MathUtils.clamp(row(y), 0, ROWS - 1)
        if (!isBlocked(start.first, start.second)) return Vector2(x, y)
        val seen = Array(COLS) { BooleanArray(ROWS) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(start)
        seen[start.first][start.second] = true
        while (queue.isNotEmpty()) {
            val (c, r) = queue.poll()
            if (!blocked[c][r]) return Vector2(center(c), center(r))
            for ((dc, dr) in ORTHOGONAL) {
                val nc = c + dc
                val nr = r + dr
                if (inBounds(nc, nr) && !seen[nc][nr]) {
                    seen[nc][nr] = true
                    queue.add(nc to nr)
                }
            }
        }
        return Vector2(x, y)
    }

    /** The nearest point to (x, y) where a circle of [radius] fits; falls back to an open tile's centre. */
    fun nearestStandable(x: Float, y: Float, radius: Float): Vector2 {
        val open = nearestOpen(x, y)
        if (fits(open, radius)) return open
        return Vector2(center(col(open.x)), center(row(open.y)))
    }

    /**
     * A* over the tile grid, then shortened by skipping waypoints that can be walked to directly.
     * Returns the waypoints after [from], ending at [to], or null if [to] can't be reached.
     */
    fun findPath(from: Vector2, to: Vector2, radius: Float): MutableList<Vector2>? {
        if (clearPath(from, to, radius)) return mutableListOf(Vector2(to))
        val start = index(col(from.x), row(from.y))
        val goal = index(col(to.x), row(to.y))
        if (isBlocked(col(to.x), row(to.y))) return null

        val cost = FloatArray(COLS * ROWS) { Float.MAX_VALUE }
        val parent = IntArray(COLS * ROWS) { -1 }
        val closed = BooleanArray(COLS * ROWS)
        val open = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        cost[start] = 0f
        open.add(start to 0f)
        while (open.isNotEmpty()) {
            val current = open.poll().first
            if (closed[current]) continue
            closed[current] = true
            if (current == goal) break
            val c = current % COLS
            val r = current / COLS
            for ((dc, dr) in NEIGHBOURS) {
                val nc = c + dc
                val nr = r + dr
                if (isBlocked(nc, nr)) continue
                val diagonal = dc != 0 && dr != 0
                // No cutting corners past an obstacle.
                if (diagonal && (isBlocked(c + dc, r) || isBlocked(c, r + dr))) continue
                val next = index(nc, nr)
                val newCost = cost[current] + if (diagonal) 1.4142f else 1f
                if (newCost < cost[next]) {
                    cost[next] = newCost
                    parent[next] = current
                    open.add(next to newCost + octile(nc, nr, goal % COLS, goal / COLS))
                }
            }
        }
        if (!closed[goal]) return null

        val tiles = mutableListOf<Vector2>()
        var node = parent[goal]
        while (node != -1 && node != start) {
            tiles.add(Vector2(center(node % COLS), center(node / COLS)))
            node = parent[node]
        }
        tiles.reverse()
        val points = mutableListOf(Vector2(from)).apply { addAll(tiles); add(Vector2(to)) }

        val path = mutableListOf<Vector2>()
        var i = 0
        while (i < points.lastIndex) {
            var j = points.lastIndex
            while (j > i + 1 && !clearPath(points[i], points[j], radius)) j--
            path.add(points[j])
            i = j
        }
        return path
    }

    /** Random ground, obstacles and decorations, keeping the given circles clear and all reachable from each other. */
    fun generate(clearZones: List<Pair<Vector2, Float>>) {
        for (c in 0 until COLS) for (r in 0 until ROWS) ground[c][r] = MathUtils.random(1)
        repeat(10) {
            val cx = MathUtils.random(COLS - 1)
            val cy = MathUtils.random(ROWS - 1)
            val radius = MathUtils.random(1.5f, 4f)
            for (c in 0 until COLS) for (r in 0 until ROWS) {
                val d = Vector2.dst(c.toFloat(), r.toFloat(), cx.toFloat(), cy.toFloat())
                if (d < radius + MathUtils.random(-0.6f, 0.6f)) ground[c][r] = 2 + MathUtils.random(1)
            }
        }

        fun allowed(c: Int, r: Int) =
            inBounds(c, r) && clearZones.none { (p, radius) -> p.dst(center(c), center(r)) < radius }

        for (attempt in 0 until 20) {
            clearObstacles()
            repeat(16) {
                // Rock ridges: short wandering walls.
                var c = MathUtils.random(COLS - 1)
                var r = MathUtils.random(ROWS - 1)
                var dir = MathUtils.random(NEIGHBOURS.size - 1)
                repeat(MathUtils.random(2, 6)) {
                    if (allowed(c, r)) addObstacle(ObstacleKind.ROCK, c, r)
                    if (MathUtils.random() < 0.3f) dir = (dir + if (MathUtils.randomBoolean()) 1 else NEIGHBOURS.size - 1) % NEIGHBOURS.size
                    c += NEIGHBOURS[dir].first
                    r += NEIGHBOURS[dir].second
                }
            }
            repeat(10) {
                // Crate stacks: up to a 2x2 block.
                val c = MathUtils.random(COLS - 2)
                val r = MathUtils.random(ROWS - 2)
                for (dc in 0..1) for (dr in 0..1) {
                    if (MathUtils.random() < 0.6f && allowed(c + dc, r + dr)) addObstacle(ObstacleKind.CRATE, c + dc, r + dr)
                }
            }
            repeat(7) {
                // Tree groves: rough blobs.
                val cx = MathUtils.random(COLS - 1)
                val cy = MathUtils.random(ROWS - 1)
                val radius = MathUtils.random(1f, 2.2f)
                for (c in cx - 2..cx + 2) for (r in cy - 2..cy + 2) {
                    val d = Vector2.dst(c.toFloat(), r.toFloat(), cx.toFloat(), cy.toFloat())
                    if (d <= radius && MathUtils.random() < 0.75f && allowed(c, r)) addObstacle(ObstacleKind.TREE, c, r)
                }
            }
            if (fillUnreachable(clearZones.map { it.first })) break
        }

        var tries = 0
        while (decorations.size < 50 && tries++ < 1000) {
            val x = MathUtils.random(TILE, WIDTH - TILE)
            val y = MathUtils.random(TILE, HEIGHT - TILE)
            if (isBlockedAt(x, y) || clearZones.first().first.dst(x, y) < 250f) continue
            if (decorations.any { Vector2.dst(it.x, it.y, x, y) < 110f }) continue
            decorations.add(Decoration(MathUtils.random(DECORATION_KINDS - 1), x, y, MathUtils.random(360f)))
        }
    }

    /**
     * Flood-fills from the first point. Returns false if any of [points] can't be reached; otherwise fills
     * enclosed pockets with rock so every open tile is reachable.
     */
    private fun fillUnreachable(points: List<Vector2>): Boolean {
        val reached = Array(COLS) { BooleanArray(ROWS) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        val start = col(points.first().x) to row(points.first().y)
        reached[start.first][start.second] = true
        queue.add(start)
        while (queue.isNotEmpty()) {
            val (c, r) = queue.poll()
            for ((dc, dr) in ORTHOGONAL) {
                val nc = c + dc
                val nr = r + dr
                if (!isBlocked(nc, nr) && !reached[nc][nr]) {
                    reached[nc][nr] = true
                    queue.add(nc to nr)
                }
            }
        }
        if (points.any { !reached[col(it.x)][row(it.y)] }) return false
        for (c in 0 until COLS) for (r in 0 until ROWS) {
            if (!blocked[c][r] && !reached[c][r]) addObstacle(ObstacleKind.ROCK, c, r)
        }
        return true
    }

    private fun clearObstacles() {
        obstacles.clear()
        for (column in blocked) column.fill(false)
    }

    private fun octile(c0: Int, r0: Int, c1: Int, r1: Int): Float {
        val dx = abs(c0 - c1).toFloat()
        val dy = abs(r0 - r1).toFloat()
        return max(dx, dy) + 0.4142f * min(dx, dy)
    }

    companion object {
        const val TILE = 64f
        const val COLS = 40
        const val ROWS = 30
        const val WIDTH = COLS * TILE
        const val HEIGHT = ROWS * TILE
        const val DECORATION_KINDS = 2

        private const val SAMPLE_STEP = 8f
        private const val FIELD_STEP = 4f
        private const val COVER_DISTANCE = Soldier.RADIUS + 14f
        private const val COVER_MIN_DOT = 0.45f
        private const val COVER_MIN_RANGE = 110f

        private val ORTHOGONAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        // Ordered around the compass so neighbouring indices are 45 degrees apart.
        private val NEIGHBOURS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

        fun col(x: Float) = MathUtils.floor(x / TILE)
        fun row(y: Float) = MathUtils.floor(y / TILE)
        fun center(cell: Int) = (cell + 0.5f) * TILE
        fun inBounds(c: Int, r: Int) = c in 0 until COLS && r in 0 until ROWS
        private fun index(c: Int, r: Int) = r * COLS + c
    }
}
