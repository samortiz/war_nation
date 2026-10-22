package com.warnation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/** Sprites from Kenney's "Top-down Shooter" pack (CC0), see assets/KENNEY_LICENSE.txt. */
class Assets : Disposable {
    private val textures = mutableListOf<Texture>()

    private val soldiers = SoldierType.entries.associateWith { load("soldier_${it.name.lowercase()}.png") }
    private val robots = SoldierType.entries.associateWith { load("robot_${it.name.lowercase()}.png") }
    val ground = listOf(load("grass1.png"), load("grass2.png"), load("dirt1.png"), load("dirt2.png"))
    val decorations = listOf(load("bush.png"), load("bush_dry.png"))
    private val rocks = listOf(load("rock1.png"), load("rock2.png"))
    private val crates = listOf(load("crate1.png"), load("crate2.png"))
    private val tree = load("tree.png")

    /** A plain white pixel, stretched and tinted to draw simple shapes in a sprite batch. */
    val pixel: TextureRegion = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pixmap ->
        pixmap.setColor(1f, 1f, 1f, 1f)
        pixmap.fill()
        val texture = Texture(pixmap)
        pixmap.dispose()
        textures.add(texture)
        TextureRegion(texture)
    }

    fun sprite(team: Team, type: SoldierType): TextureRegion = (if (team == Team.PLAYER) soldiers else robots).getValue(type)

    fun obstacle(o: Obstacle): TextureRegion = when (o.kind) {
        ObstacleKind.ROCK -> rocks[o.variant]
        ObstacleKind.CRATE -> crates[o.variant]
        ObstacleKind.TREE -> tree
    }

    private fun load(name: String): TextureRegion {
        val texture = Texture(Gdx.files.internal(name))
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        textures.add(texture)
        return TextureRegion(texture)
    }

    override fun dispose() = textures.forEach { it.dispose() }
}
