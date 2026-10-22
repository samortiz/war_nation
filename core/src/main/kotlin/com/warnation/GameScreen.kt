package com.warnation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GameScreen(
    private val game: WarNationGame,
    private val squad: List<SoldierType>,
    /** Index into [Campaign.MISSIONS], or null for a skirmish. */
    private val missionIndex: Int? = null,
) : ScreenAdapter() {
    private val assets = game.assets
    private val fonts = game.fonts
    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(1280f, 720f, camera)
    private val hudViewport = ScreenViewport()
    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val layout = GlyphLayout()

    private val mission = missionIndex?.let { Campaign.MISSIONS[it] }
    private var battle = newBattle()
    private var outcomeRecorded = false
    private var battleTime = 0f
    private val selection = mutableListOf<Soldier>()

    /** Whether ground taps order an attack-move (fight on the way) or a run (ignore enemies). */
    private var attackMove = true

    /** Frozen in time; the player can still look around, select and give orders, which play out on resume. */
    private var paused = false
    private var gameOverTime = 0f

    // Feedback marker shown where the last order was given.
    private val marker = Vector2()
    private var markerTime = 0f
    private var markerIsAttack = false

    private val tmp = Vector2()
    private val tmp2 = Vector2()

    private val touchSlop = 20f * max(1f, Gdx.graphics.density)
    private val gestures = GestureDetector(touchSlop, 0.4f, 1.1f, 0.15f, Gestures())
    private val boxSelect = BoxSelect()

    init {
        focusCameraOnSquad()
    }

    override fun render(delta: Float) {
        val dt = min(delta, 1f / 30f)
        if (!paused) {
            battle.update(dt)
            battleTime += dt
            markerTime -= dt
        }
        selection.removeAll { !it.alive }
        boxSelect.update(dt)
        if (battle.outcome != Outcome.PLAYING) {
            gameOverTime += dt
            if (!outcomeRecorded) {
                game.squad.recordOutcome(battle.outcome)
                if (battle.outcome == Outcome.VICTORY && missionIndex != null) game.campaign.recordVictory(missionIndex)
            }
            outcomeRecorded = true
        }

        clampCamera()
        viewport.apply()
        camera.update()
        ScreenUtils.clear(0.08f, 0.1f, 0.08f, 1f)
        batch.projectionMatrix = camera.combined
        shapes.projectionMatrix = camera.combined

        batch.begin()
        drawGround()
        drawCorpses()
        drawDecorations()
        drawObstacles()
        batch.end()

        drawScorches()
        drawOrders()

        batch.begin()
        drawSoldiers()
        batch.end()

        drawFireLines()
        drawEffectsAndHealth()
        drawBadges()
        drawHud()
    }

    private fun drawGround() {
        val halfW = viewport.worldWidth * camera.zoom / 2f
        val halfH = viewport.worldHeight * camera.zoom / 2f
        val c0 = max(0, ((camera.position.x - halfW) / Terrain.TILE).toInt())
        val c1 = min(Terrain.COLS - 1, ((camera.position.x + halfW) / Terrain.TILE).toInt())
        val r0 = max(0, ((camera.position.y - halfH) / Terrain.TILE).toInt())
        val r1 = min(Terrain.ROWS - 1, ((camera.position.y + halfH) / Terrain.TILE).toInt())
        for (c in c0..c1) for (r in r0..r1) {
            // Overlap tiles by a pixel to hide seams when zoomed.
            batch.draw(assets.ground[battle.terrain.ground[c][r]], c * Terrain.TILE, r * Terrain.TILE, Terrain.TILE + 1f, Terrain.TILE + 1f)
        }
    }

    private fun drawCorpses() {
        for (corpse in battle.corpses) {
            val alpha = 1f - corpse.age / Battle.CORPSE_LIFETIME
            batch.setColor(0.25f, 0.2f, 0.2f, 0.8f * alpha)
            drawRotated(assets.sprite(corpse.team, corpse.type), corpse.x, corpse.y, corpse.angle)
        }
        batch.color = Color.WHITE
    }

    private fun drawDecorations() {
        for (d in battle.terrain.decorations) {
            val region = assets.decorations[d.kind]
            batch.draw(region, d.x - 32f, d.y - 32f, 32f, 32f, 64f, 64f, 1f, 1f, d.rotation)
        }
    }

    private fun drawObstacles() {
        val obstacles = battle.terrain.obstacles
        // Soft drop shadow first, then the obstacles themselves.
        batch.setColor(0f, 0f, 0f, 0.3f)
        for (o in obstacles) drawObstacle(o, SHADOW_OFFSET, -SHADOW_OFFSET)
        batch.color = Color.WHITE
        for (o in obstacles) drawObstacle(o, 0f, 0f)
    }

    private fun drawObstacle(o: Obstacle, dx: Float, dy: Float) {
        val scale = when (o.kind) {
            ObstacleKind.ROCK -> 1.85f
            ObstacleKind.TREE -> 1.3f
            ObstacleKind.CRATE -> 1f
        }
        val half = Terrain.TILE / 2f
        val x = o.col * Terrain.TILE + dx
        val y = o.row * Terrain.TILE + dy
        batch.draw(assets.obstacle(o), x, y, half, half, Terrain.TILE, Terrain.TILE, scale, scale, o.rotation)
    }

    private fun drawSoldiers() {
        for (s in battle.soldiers) {
            drawRotated(assets.sprite(s.team, s.type), s.pos.x, s.pos.y, s.angle)
            if (s.type == SoldierType.ROCKETEER) drawLauncher(s)
        }
    }

    /** The rocket tube on a rocketeer's shoulder, rotated with the body. */
    private fun drawLauncher(s: Soldier) {
        val back = 8f
        val length = s.type.gunForward + back
        val width = 9f
        val side = s.type.gunSide
        batch.setColor(0.22f, 0.27f, 0.18f, 1f)
        batch.draw(assets.pixel, s.pos.x - back, s.pos.y + side - width / 2f, back, width / 2f - side, length, width, 1f, 1f, s.angle)
        batch.setColor(0.12f, 0.12f, 0.12f, 1f)
        batch.draw(assets.pixel, s.pos.x - back, s.pos.y + side - width / 2f, back, width / 2f - side, 4f, width, 1f, 1f, s.angle)
        batch.color = Color.WHITE
    }

    private fun drawScorches() {
        if (battle.explosions.isEmpty()) return
        beginShapes(ShapeRenderer.ShapeType.Filled)
        for (e in battle.explosions) {
            shapes.setColor(0.1f, 0.08f, 0.05f, 0.4f * (1f - e.age / Battle.SCORCH_LIFETIME))
            shapes.circle(e.x, e.y, e.radius * 0.6f, 20)
        }
        shapes.end()
    }

    /** A letter over each specialist's health bar: S(niper), G(renadier), R(ocketeer). */
    private fun drawBadges() {
        batch.projectionMatrix = camera.combined
        batch.begin()
        val font = fonts.world
        for (s in battle.soldiers) {
            if (s.type.badge.isEmpty()) continue
            font.color = if (s.team == Team.PLAYER) Color(0.75f, 1f, 0.75f, 1f) else Color(1f, 0.75f, 0.7f, 1f)
            font.draw(batch, s.type.badge, s.pos.x - 36f, s.pos.y + Soldier.RADIUS + 22f)
        }
        batch.end()
    }

    /** Sprites face right; the body is a circle on the left whose diameter equals the sprite height. */
    private fun drawRotated(region: TextureRegion, x: Float, y: Float, angle: Float) {
        val w = region.regionWidth.toFloat()
        val h = region.regionHeight.toFloat()
        val origin = h / 2f
        batch.draw(region, x - origin, y - origin, origin, origin, w, h, 1f, 1f, angle)
    }

    /**
     * The part of a soldier's vision cone it can actually see and shoot into, cut short by obstacles.
     * Recomputed only when the soldier moves or turns.
     */
    private class Cone(rays: Int) {
        val origin = Vector2()
        var angle = 0f
        var fromDegrees = 0f
        var sweepDegrees = 0f
        val reach = FloatArray(rays)
        var computed = false
    }

    private val fields = mutableMapOf<Soldier, Cone>()
    private val enemyCones = mutableMapOf<Soldier, Cone>()

    private fun coneOf(cache: MutableMap<Soldier, Cone>, s: Soldier, range: Float, degreesPerRay: Float): Cone {
        val fov = s.type.fieldOfView
        val cone = cache.getOrPut(s) { Cone((fov / degreesPerRay).toInt() + 1) }
        val moved = !cone.origin.epsilonEquals(s.pos, CONE_RECOMPUTE_DISTANCE)
        val turned = abs(Soldier.angleBetween(cone.angle, s.angle)) > CONE_RECOMPUTE_DEGREES
        if (!cone.computed || moved || turned) {
            cone.computed = true
            cone.origin.set(s.pos)
            cone.angle = s.angle
            cone.fromDegrees = s.angle - fov / 2f
            cone.sweepDegrees = fov
            battle.terrain.fieldOfFire(s.pos, range, Battle.PEEK_DISTANCE, cone.fromDegrees, fov, cone.reach)
        }
        return cone
    }

    private fun rayEnd(cone: Cone, i: Int, out: Vector2): Vector2 {
        val degrees = cone.fromDegrees + cone.sweepDegrees * i / (cone.reach.size - 1)
        return out.set(cone.origin).add(MathUtils.cosDeg(degrees) * cone.reach[i], MathUtils.sinDeg(degrees) * cone.reach[i])
    }

    /** Fills cones as one merged area (the depth buffer stops overlaps stacking), then outlines them. */
    private fun drawCones(cones: List<Cone>, fill: Color, outline: Color) {
        if (cones.isEmpty()) return
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LESS)
        beginShapes(ShapeRenderer.ShapeType.Filled)
        shapes.color = fill
        for (cone in cones) {
            for (i in 0 until cone.reach.size - 1) {
                val a = rayEnd(cone, i, tmp)
                val b = rayEnd(cone, i + 1, tmp2)
                shapes.triangle(cone.origin.x, cone.origin.y, a.x, a.y, b.x, b.y)
            }
        }
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        beginShapes(ShapeRenderer.ShapeType.Line)
        shapes.color = outline
        for (cone in cones) {
            shapes.line(cone.origin, rayEnd(cone, 0, tmp))
            for (i in 0 until cone.reach.size - 1) shapes.line(rayEnd(cone, i, tmp), rayEnd(cone, i + 1, tmp2))
            shapes.line(cone.origin, rayEnd(cone, cone.reach.size - 1, tmp))
        }
        shapes.end()
    }

    /** Faint red cones showing where each robot on screen is looking: walk outside them to stay unseen. */
    private fun drawEnemyVision() {
        val enemies = battle.soldiers.filter { it.team == Team.ENEMY }
        enemyCones.keys.retainAll(enemies.toSet())
        val viewRadius = Vector2.len(viewport.worldWidth, viewport.worldHeight) * camera.zoom / 2f
        val cones = enemies
            .filter { it.pos.dst(camera.position.x, camera.position.y) < viewRadius + it.range }
            .map { coneOf(enemyCones, it, max(Battle.AGGRO_RADIUS, it.range), ENEMY_CONE_DEGREES_PER_RAY) }
        drawCones(cones, ENEMY_CONE_FILL, ENEMY_CONE_OUTLINE)
    }

    /**
     * Highlights the ground the selected soldiers can see and hit: their forward cone within weapon range,
     * minus whatever obstacles hide. Grenadiers can also lob anywhere in range a squadmate spots, shown
     * fainter. Outlines are faint for groups, where they'd otherwise criss-cross the merged area.
     */
    private fun drawFieldsOfFire() {
        fields.keys.retainAll(selection.toSet())
        if (selection.isEmpty()) return
        val grenadiers = selection.filter { it.type.indirect }
        if (grenadiers.isNotEmpty()) {
            // The lob range reaches over obstacles, so it's a full circle with a dashed edge.
            beginShapes(ShapeRenderer.ShapeType.Filled)
            shapes.color = LOB_FILL
            for (s in grenadiers) shapes.circle(s.pos.x, s.pos.y, s.range, 96)
            shapes.color = LOB_OUTLINE
            for (s in grenadiers) {
                for (i in 0 until LOB_DASHES) {
                    val a0 = 360f * i / LOB_DASHES
                    val a1 = a0 + 360f / LOB_DASHES * 0.6f
                    shapes.rectLine(
                        s.pos.x + MathUtils.cosDeg(a0) * s.range, s.pos.y + MathUtils.sinDeg(a0) * s.range,
                        s.pos.x + MathUtils.cosDeg(a1) * s.range, s.pos.y + MathUtils.sinDeg(a1) * s.range,
                        LOB_DASH_WIDTH,
                    )
                }
            }
            shapes.end()
        }
        val outline = Color(0.6f, 1f, 0.6f, if (selection.size == 1) 0.4f else 0.15f)
        drawCones(selection.map { coneOf(fields, it, it.range, 1f) }, FIELD_FILL, outline)
    }

    private fun drawOrders() {
        drawEnemyVision()
        drawFieldsOfFire()
        if (selection.isNotEmpty()) {
            beginShapes(ShapeRenderer.ShapeType.Filled)
            shapes.setColor(0.3f, 1f, 0.4f, 0.25f)
            for (s in selection) shapes.circle(s.pos.x, s.pos.y, Soldier.RADIUS + 8f, 32)
            shapes.end()

            beginShapes(ShapeRenderer.ShapeType.Line)
            for (s in selection) drawSelected(s)
            if (selection.size == 1 && !selection[0].type.indirect) {
                // Full weapon range, for comparison with the field of fire inside it (grenadiers show their own).
                val s = selection[0]
                shapes.setColor(1f, 1f, 1f, 0.15f)
                shapes.circle(s.pos.x, s.pos.y, s.range, 96)
            }
            shapes.end()
        }
        drawMarker()
    }

    private fun drawSelected(s: Soldier) {
        shapes.setColor(0.4f, 1f, 0.5f, 0.9f)
        shapes.circle(s.pos.x, s.pos.y, Soldier.RADIUS + 8f, 32)
        shapes.circle(s.pos.x, s.pos.y, Soldier.RADIUS + 9f, 32)
        if (s.path.isNotEmpty()) {
            // The route the soldier will take around obstacles.
            shapes.setColor(0.4f, 1f, 0.5f, 0.5f)
            var from = s.pos
            for (point in s.path) {
                shapes.line(from, point)
                from = point
            }
        }
        s.attackTarget?.let {
            shapes.setColor(1f, 0.3f, 0.3f, 0.6f)
            if (s.path.isEmpty()) shapes.line(s.pos, it.pos)
            shapes.circle(it.pos.x, it.pos.y, Soldier.RADIUS + 6f, 32)
        }
    }

    private fun drawMarker() {
        if (markerTime <= 0f) return
        beginShapes(ShapeRenderer.ShapeType.Line)
        val t = markerTime / MARKER_TIME
        if (markerIsAttack) shapes.setColor(1f, 0.3f, 0.3f, t) else shapes.setColor(0.4f, 1f, 0.5f, t)
        shapes.circle(marker.x, marker.y, 8f + 18f * t, 24)
        shapes.end()
    }

    private fun drawEffectsAndHealth() {
        beginShapes(ShapeRenderer.ShapeType.Filled)
        for (p in battle.projectiles) drawProjectile(p)
        for (e in battle.explosions) {
            if (e.age > Battle.BLAST_TIME) continue
            val t = e.age / Battle.BLAST_TIME
            shapes.setColor(1f, 0.5f, 0.1f, 0.8f * (1f - t))
            shapes.circle(e.x, e.y, e.radius * (0.5f + 0.5f * t), 24)
            shapes.setColor(1f, 0.9f, 0.5f, 0.9f * (1f - t))
            shapes.circle(e.x, e.y, e.radius * 0.35f * (1f - t), 16)
        }
        for (spark in battle.sparks) {
            val t = spark.age / Battle.SPARK_LIFETIME
            shapes.setColor(1f, 1f, 0.8f, 1f - t)
            shapes.circle(spark.x, spark.y, 3f + 7f * t, 10)
        }
        for (s in battle.soldiers) {
            if (s.muzzleFlash > 0f) {
                s.gunTip(tmp)
                shapes.setColor(1f, 0.85f, 0.3f, 0.9f)
                shapes.circle(tmp.x, tmp.y, if (s.type.weapon == Weapon.ROCKET) 12f else 7f, 12)
            }
        }
        for (s in battle.soldiers) {
            val x = s.pos.x - 22f
            val y = s.pos.y + Soldier.RADIUS + 10f
            shapes.setColor(0f, 0f, 0f, 0.6f)
            shapes.rect(x - 1f, y - 1f, 46f, 8f)
            if (s.team == Team.PLAYER) shapes.setColor(0.3f, 0.9f, 0.3f, 1f) else shapes.setColor(0.95f, 0.3f, 0.25f, 1f)
            shapes.rect(x, y, 44f * (s.hp / s.maxHp), 6f)
        }
        shapes.end()
    }

    /**
     * A line from each shooter to the enemy it's firing at, flaring briefly on every shot. Shots that
     * cover will mostly stop end in a bar at the cover; grenadiers show a dashed arc.
     */
    private fun drawFireLines() {
        beginShapes(ShapeRenderer.ShapeType.Filled)
        for (s in battle.soldiers) {
            val target = s.firingAt ?: continue
            val sinceShot = s.fireInterval - s.cooldown
            val flare = MathUtils.clamp(1f - sinceShot / FIRE_LINE_FLARE_TIME, 0f, 1f)
            val alpha = 0.22f + 0.5f * flare
            val (r, g, b) = if (s.team == Team.PLAYER) Triple(0.55f, 1f, 0.55f) else Triple(1f, 0.45f, 0.4f)
            val from = s.gunTip(tmp)

            if (s.type.weapon == Weapon.GRENADE) {
                shapes.setColor(r, g, b, alpha)
                drawDashedArc(from.x, from.y, target.pos.x, target.pos.y)
                continue
            }
            if (battle.shotsBlockedByCover(s, target)) {
                // Greyed out and cut short: the target's cover will soak up most of these shots.
                val stop = battle.coverPoint(s, target, tmp2)
                shapes.setColor(0.8f, 0.8f, 0.7f, alpha * 0.8f)
                shapes.rectLine(from.x, from.y, stop.x, stop.y, FIRE_LINE_WIDTH)
                val nx = -(stop.y - from.y)
                val ny = stop.x - from.x
                val len = max(0.001f, sqrt(nx * nx + ny * ny))
                val half = 9f
                shapes.rectLine(stop.x - nx / len * half, stop.y - ny / len * half, stop.x + nx / len * half, stop.y + ny / len * half, 3f)
                continue
            }
            shapes.setColor(r, g, b, alpha)
            tmp2.set(from).sub(target.pos).nor().scl(Soldier.RADIUS).add(target.pos)
            shapes.rectLine(from.x, from.y, tmp2.x, tmp2.y, FIRE_LINE_WIDTH)
        }
        shapes.end()
    }

    /** The lob path of a grenade, dashed, peaking at the same height drawProjectile uses. */
    private fun drawDashedArc(x0: Float, y0: Float, x1: Float, y1: Float) {
        val distance = Vector2.dst(x0, y0, x1, y1)
        val peak = distance * 0.3f
        val segments = max(4, (distance / 8f).toInt())
        var px = x0
        var py = y0
        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t + 4f * peak * t * (1f - t)
            if (i % 2 == 0) shapes.rectLine(px, py, x, y, FIRE_LINE_WIDTH)
            px = x
            py = y
        }
    }

    private fun drawProjectile(p: Projectile) {
        val x = p.pos.x
        val y = p.pos.y
        when (p.weapon) {
            Weapon.RIFLE -> {
                shapes.setColor(1f, 0.9f, 0.4f, 1f)
                shapes.rectLine(x, y, x - p.dir.x * 10f, y - p.dir.y * 10f, 3f)
            }
            Weapon.SNIPER_RIFLE -> {
                shapes.setColor(1f, 1f, 1f, 0.9f)
                shapes.rectLine(x, y, x - p.dir.x * 40f, y - p.dir.y * 40f, 2f)
            }
            Weapon.GRENADE -> {
                // Shadow on the ground, grenade raised by its arc.
                val t = p.progress
                val height = 4f * p.start.dst(p.dest!!) * 0.3f * t * (1f - t)
                shapes.setColor(0f, 0f, 0f, 0.35f)
                shapes.circle(x, y, 5f, 10)
                shapes.setColor(0.2f, 0.3f, 0.15f, 1f)
                shapes.circle(x, y + height, 5f + height * 0.03f, 10)
            }
            Weapon.ROCKET -> {
                shapes.setColor(1f, 0.6f, 0.15f, 0.8f)
                shapes.circle(x - p.dir.x * 12f, y - p.dir.y * 12f, 5f, 10)
                shapes.setColor(0.35f, 0.38f, 0.32f, 1f)
                shapes.rectLine(x, y, x - p.dir.x * 14f, y - p.dir.y * 14f, 5f)
            }
        }
    }

    private fun drawHud() {
        hudViewport.apply()
        val w = hudViewport.worldWidth
        val h = hudViewport.worldHeight
        val pad = dp(12f)
        val buttons = hudButtons()

        shapes.projectionMatrix = hudViewport.camera.combined
        if (battle.outcome == Outcome.PLAYING) {
            if (paused) {
                beginShapes(ShapeRenderer.ShapeType.Filled)
                shapes.setColor(0f, 0f, 0f, 0.3f)
                shapes.rect(0f, 0f, w, h)
                shapes.end()
            }
            boxSelect.draw()
        } else {
            beginShapes(ShapeRenderer.ShapeType.Filled)
            shapes.setColor(0f, 0f, 0f, min(0.6f, gameOverTime))
            shapes.rect(0f, 0f, w, h)
            shapes.end()
        }
        drawButtonBackgrounds(buttons)

        batch.projectionMatrix = hudViewport.camera.combined
        batch.begin()
        val selectedText = if (selection.isEmpty()) "" else "    Selected: ${selection.size}"
        fonts.body.color = Color.WHITE
        fonts.body.draw(batch, "Squad: ${battle.count(Team.PLAYER)}    Enemies: ${battle.count(Team.ENEMY)}$selectedText", pad, h - pad)
        if (paused && battle.outcome == Outcome.PLAYING) {
            layout.setText(fonts.title, "PAUSED", Color.WHITE, w, Align.center, false)
            fonts.title.draw(batch, layout, 0f, h / 2f + layout.height / 2f)
            layout.setText(fonts.body, "Orders still work: they play out when you resume", Color(1f, 1f, 1f, 0.8f), w, Align.center, false)
            fonts.body.draw(batch, layout, 0f, h / 2f - dp(30f))
        } else if (mission != null && battle.outcome == Outcome.PLAYING && battleTime < BRIEFING_TIME) {
            // The briefing fades out over its last second.
            val alpha = min(1f, BRIEFING_TIME - battleTime)
            layout.setText(fonts.heading, "MISSION ${missionIndex!! + 1}: ${mission.title.uppercase()}", Color(0.91f, 0.83f, 0.42f, alpha), w, Align.center, false)
            fonts.heading.draw(batch, layout, 0f, h - pad - dp(40f))
            layout.setText(fonts.body, mission.briefing, Color(1f, 1f, 1f, alpha), w * 0.6f, Align.center, true)
            fonts.body.draw(batch, layout, w * 0.2f, h - pad - dp(80f))
        }

        if (battle.outcome == Outcome.PLAYING) {
            val hint = if (selection.isEmpty()) "Tap a soldier  ·  double-tap: whole squad  ·  hold, then drag: box select  ·  red cones: where robots look"
            else if (attackMove) "Tap ground to move, shooting on the way  ·  tap enemy to attack  ·  RUN to ignore enemies"
            else "Tap ground to run, ignoring enemies  ·  tap enemy to attack  ·  FIGHT to shoot on the way"
            fonts.small.color = Color(1f, 1f, 1f, 0.75f)
            fonts.small.draw(batch, hint, pad, pad + fonts.small.capHeight)
        } else {
            val won = battle.outcome == Outcome.VICTORY
            val color = if (won) Color(0.5f, 1f, 0.5f, 1f) else Color(1f, 0.4f, 0.35f, 1f)
            layout.setText(fonts.title, if (won) "VICTORY" else "DEFEAT", color, w, Align.center, false)
            fonts.title.draw(batch, layout, 0f, h / 2f + layout.height + dp(10f))
            val subtitle = when {
                mission == null -> null
                won && game.campaign.finished && missionIndex == Campaign.MISSIONS.lastIndex -> "Campaign complete. The robots are beaten!"
                won -> "Mission ${missionIndex!! + 1} of ${Campaign.MISSIONS.size} complete"
                else -> "Mission ${missionIndex!! + 1}: ${mission.title}"
            }
            if (subtitle != null) {
                layout.setText(fonts.body, subtitle, Color.WHITE, w, Align.center, false)
                fonts.body.draw(batch, layout, 0f, h / 2f)
            }
        }
        for (b in buttons) {
            layout.setText(fonts.heading, b.text, Color.WHITE, b.rect.width, Align.center, false)
            fonts.heading.draw(batch, layout, b.rect.x, b.rect.y + (b.rect.height + layout.height) / 2f)
        }
        batch.end()
    }

    private class HudButton(val text: String, val rect: Rectangle, val primary: Boolean, val action: () -> Unit)

    /** The on-screen buttons for the current state, in HUD coordinates. */
    private fun hudButtons(): List<HudButton> {
        val w = hudViewport.worldWidth
        val h = hudViewport.worldHeight
        val pad = dp(12f)
        return when {
            battle.outcome == Outcome.PLAYING -> listOf(
                HudButton("ALL", Rectangle(w - pad - dp(80f), pad, dp(80f), dp(44f)), false) { selectAll() },
                HudButton(
                    if (attackMove) "FIGHT" else "RUN",
                    Rectangle(w - pad - dp(80f), pad + dp(52f), dp(80f), dp(44f)),
                    attackMove,
                ) { attackMove = !attackMove },
                HudButton("MENU", Rectangle(w - pad - dp(80f), h - pad - dp(40f), dp(80f), dp(40f)), false) { game.showHome() },
                HudButton(
                    if (paused) "RESUME" else "PAUSE",
                    Rectangle(w - pad - dp(80f) - dp(8f) - dp(110f), h - pad - dp(40f), dp(110f), dp(40f)),
                    paused,
                ) { paused = !paused },
            )
            gameOverTime > RESTART_DELAY -> {
                val bw = dp(210f)
                val bh = dp(52f)
                val gap = dp(16f)
                val y = h / 2f - dp(80f)
                val next = missionIndex?.plus(1)?.takeIf { battle.outcome == Outcome.VICTORY && it < Campaign.MISSIONS.size }
                listOf(
                    if (next != null) HudButton("NEXT MISSION", Rectangle(w / 2f - gap / 2f - bw, y, bw, bh), true) { game.startBattle(next) }
                    else HudButton(if (mission != null && battle.outcome == Outcome.DEFEAT) "RETRY" else "PLAY AGAIN", Rectangle(w / 2f - gap / 2f - bw, y, bw, bh), true) { restart() },
                    HudButton("SQUAD", Rectangle(w / 2f + gap / 2f, y, bw, bh), false) { game.showHome() },
                )
            }
            else -> emptyList()
        }
    }

    private fun drawButtonBackgrounds(buttons: List<HudButton>) {
        if (buttons.isEmpty()) return
        beginShapes(ShapeRenderer.ShapeType.Filled)
        for (b in buttons) {
            if (b.primary) shapes.setColor(0.33f, 0.55f, 0.22f, 0.95f) else shapes.setColor(0f, 0f, 0f, 0.5f)
            shapes.rect(b.rect.x, b.rect.y, b.rect.width, b.rect.height)
        }
        shapes.end()
        beginShapes(ShapeRenderer.ShapeType.Line)
        shapes.setColor(0.4f, 1f, 0.5f, 0.8f)
        for (b in buttons) shapes.rect(b.rect.x, b.rect.y, b.rect.width, b.rect.height)
        shapes.end()
    }

    private fun onTap(screenX: Float, screenY: Float, count: Int) {
        val hudPoint = hudViewport.unproject(tmp.set(screenX, screenY))
        hudButtons().firstOrNull { it.rect.contains(hudPoint) }?.let {
            it.action()
            return
        }
        if (battle.outcome != Outcome.PLAYING) return
        val p = viewport.unproject(tmp.set(screenX, screenY))
        val hit = battle.soldierAt(p.x, p.y, TAP_RADIUS)
        when {
            hit != null && hit.team == Team.PLAYER -> when {
                count >= 2 -> selectAll()
                selection.size == 1 && selection[0] === hit -> selection.clear()
                else -> {
                    selection.clear()
                    selection.add(hit)
                }
            }
            selection.isEmpty() -> {}
            hit != null -> {
                for (s in selection) battle.orderAttack(s, hit)
                showMarker(hit.pos, attack = true)
            }
            else -> {
                battle.orderGroupMove(selection, p, attackMove)
                showMarker(battle.terrain.nearestStandable(p.x, p.y, Soldier.RADIUS), attack = false)
            }
        }
    }

    private fun selectAll() {
        selection.clear()
        selection.addAll(battle.soldiers.filter { it.team == Team.PLAYER })
    }

    /** Selects the player's soldiers inside the screen-space rectangle between two corners. */
    private fun selectInBox(corner1: Vector2, corner2: Vector2) {
        val a = viewport.unproject(tmp.set(corner1))
        val b = viewport.unproject(tmp2.set(corner2))
        val box = Rectangle(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
        selection.clear()
        selection.addAll(battle.soldiers.filter { it.team == Team.PLAYER && box.contains(it.pos) })
    }

    private fun showMarker(at: Vector2, attack: Boolean) {
        marker.set(at)
        markerTime = MARKER_TIME
        markerIsAttack = attack
    }

    private fun newBattle() =
        if (mission == null) Battle(playerSquad = squad)
        else Battle(playerSquad = squad, enemySquads = mission.enemies, mapSeed = mission.mapSeed)

    private fun restart() {
        battle = newBattle()
        battleTime = 0f
        paused = false
        fields.clear()
        enemyCones.clear()
        outcomeRecorded = false
        selection.clear()
        gameOverTime = 0f
        markerTime = 0f
        focusCameraOnSquad()
    }

    private fun focusCameraOnSquad() {
        camera.zoom = 1f
        camera.position.set(battle.playerSpawn.x + 300f, battle.playerSpawn.y + 150f, 0f)
    }

    private fun clampCamera() {
        val halfW = viewport.worldWidth * camera.zoom / 2f
        val halfH = viewport.worldHeight * camera.zoom / 2f
        camera.position.x = if (halfW * 2f >= Terrain.WIDTH) Terrain.WIDTH / 2f
        else MathUtils.clamp(camera.position.x, halfW, Terrain.WIDTH - halfW)
        camera.position.y = if (halfH * 2f >= Terrain.HEIGHT) Terrain.HEIGHT / 2f
        else MathUtils.clamp(camera.position.y, halfH, Terrain.HEIGHT - halfH)
    }

    private fun dp(value: Float) = fonts.dp(value)

    /** SpriteBatch.end() turns blending off, so re-enable it or translucent shapes draw opaque. */
    private fun beginShapes(type: ShapeRenderer.ShapeType) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(type)
    }

    private fun setZoom(zoom: Float) {
        camera.zoom = MathUtils.clamp(zoom, MIN_ZOOM, MAX_ZOOM)
    }

    override fun show() {
        Gdx.input.inputProcessor = InputMultiplexer(boxSelect, gestures, MouseWheelZoom(), BackToHome())
    }

    /** The app went to the background (e.g. a phone call): don't let the fight carry on unseen. */
    override fun pause() {
        if (battle.outcome == Outcome.PLAYING) paused = true
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        hudViewport.update(width, height, true)
    }

    override fun dispose() {
        batch.dispose()
        shapes.dispose()
    }

    private inner class Gestures : GestureDetector.GestureAdapter() {
        private var pinchStartZoom = 1f

        override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean {
            pinchStartZoom = camera.zoom
            return false
        }

        override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
            onTap(x, y, count)
            return true
        }

        override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
            val worldPerPixel = camera.zoom * viewport.worldWidth / viewport.screenWidth
            camera.translate(-deltaX * worldPerPixel, deltaY * worldPerPixel)
            return true
        }

        override fun zoom(initialDistance: Float, distance: Float): Boolean {
            setZoom(pinchStartZoom * initialDistance / distance)
            return true
        }
    }

    /**
     * Hold a finger still briefly, then drag to draw a selection box. A quick drag still pans:
     * once the hold time passes, the gesture detector is cancelled for that touch and this takes over.
     */
    private inner class BoxSelect : InputAdapter() {
        private var tracking = false
        private var armed = false
        private var dragging = false
        private var heldTime = 0f
        private val start = Vector2()
        private val end = Vector2()

        fun update(dt: Float) {
            if (!tracking || armed) return
            heldTime += dt
            if (heldTime >= BOX_HOLD_TIME) {
                armed = true
                gestures.cancel()
            }
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            // A second finger means a pinch, not a box.
            tracking = pointer == 0 && battle.outcome == Outcome.PLAYING
            armed = false
            dragging = false
            heldTime = 0f
            start.set(screenX.toFloat(), screenY.toFloat())
            end.set(start)
            return false
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (pointer != 0 || !tracking) return false
            end.set(screenX.toFloat(), screenY.toFloat())
            if (start.dst(end) > touchSlop) {
                if (armed) dragging = true else tracking = false
            }
            return false
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (pointer != 0 || !tracking) return false
            tracking = false
            if (!armed) return false
            armed = false
            // A slow tap that never moved still counts as a tap.
            if (dragging) selectInBox(start, end) else onTap(screenX.toFloat(), screenY.toFloat(), 1)
            dragging = false
            return false
        }

        fun draw() {
            if (!armed) return
            val a = hudViewport.unproject(tmp.set(start))
            val ax = a.x
            val ay = a.y
            val b = hudViewport.unproject(tmp.set(end))
            beginShapes(ShapeRenderer.ShapeType.Line)
            shapes.setColor(0.4f, 1f, 0.5f, 0.9f)
            if (dragging) shapes.rect(min(ax, b.x), min(ay, b.y), abs(ax - b.x), abs(ay - b.y))
            else shapes.circle(ax, ay, dp(24f), 32)
            shapes.end()
            if (dragging) {
                beginShapes(ShapeRenderer.ShapeType.Filled)
                shapes.setColor(0.4f, 1f, 0.5f, 0.12f)
                shapes.rect(min(ax, b.x), min(ay, b.y), abs(ax - b.x), abs(ay - b.y))
                shapes.end()
            }
        }
    }

    private inner class BackToHome : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            when (keycode) {
                Input.Keys.BACK, Input.Keys.ESCAPE -> game.showHome()
                Input.Keys.P, Input.Keys.SPACE -> if (battle.outcome == Outcome.PLAYING) paused = !paused
                else -> return false
            }
            return true
        }
    }

    private inner class MouseWheelZoom : InputAdapter() {
        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            setZoom(camera.zoom * (1f + amountY * 0.1f))
            return true
        }
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 1.6f
        const val TAP_RADIUS = 40f
        const val MARKER_TIME = 0.6f
        const val RESTART_DELAY = 1f
        const val BRIEFING_TIME = 6f
        const val SHADOW_OFFSET = 5f
        const val BOX_HOLD_TIME = 0.3f
        const val FIRE_LINE_WIDTH = 2f
        const val CONE_RECOMPUTE_DISTANCE = 1.5f
        const val CONE_RECOMPUTE_DEGREES = 1f
        const val ENEMY_CONE_DEGREES_PER_RAY = 2f
        val FIELD_FILL = Color(0.55f, 1f, 0.55f, 0.18f)
        val LOB_FILL = Color(0.55f, 1f, 0.55f, 0.1f)
        val LOB_OUTLINE = Color(0.6f, 1f, 0.6f, 0.55f)
        const val LOB_DASHES = 48
        const val LOB_DASH_WIDTH = 2.5f
        val ENEMY_CONE_FILL = Color(1f, 0.35f, 0.3f, 0.1f)
        val ENEMY_CONE_OUTLINE = Color(1f, 0.4f, 0.35f, 0.25f)
        const val FIRE_LINE_FLARE_TIME = 0.25f
    }
}
