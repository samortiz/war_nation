package com.warnation.desktop

import com.badlogic.gdx.Files
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.utils.ScreenUtils
import com.warnation.WarNationGame

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("War Nation")
        setWindowedMode(1280, 720)
        useVsync(true)
        setForegroundFPS(60)
    }
    val screenshotPath = System.getenv("WARNATION_SCREENSHOT")
    // Screenshot runs keep their saved squad separate from the player's own.
    System.getenv("WARNATION_PREFS_DIR")?.let { config.setPreferencesConfig(it, Files.FileType.Absolute) }
    Lwjgl3Application(if (screenshotPath != null) ScreenshotGame(screenshotPath) else WarNationGame(), config)
}

/**
 * Dev aid: saves a PNG of frame WARNATION_SCREENSHOT_FRAME (default 120) and exits. With
 * WARNATION_SCENE=battle it skips the home screen, double-taps a soldier to select the squad, orders a
 * group move toward the nearest robots and pans after it. WARNATION_SCENE=select taps one soldier
 * (at WARNATION_TAP_X/Y) to show its field of fire. WARNATION_MISSION (1-based) plays that campaign mission
 * instead of a skirmish. WARNATION_SCENE=missions opens the home screen's missions tab.
 */
private class ScreenshotGame(private val path: String) : WarNationGame() {
    private var frames = 0
    private val captureFrame = System.getenv("WARNATION_SCREENSHOT_FRAME")?.toIntOrNull() ?: 120
    private val scene = System.getenv("WARNATION_SCENE")
    private val battle = scene == "battle"
    private val mission = System.getenv("WARNATION_MISSION")?.toIntOrNull()?.minus(1)

    private fun tap(x: Int, y: Int) {
        Gdx.input.inputProcessor.touchDown(x, y, 0, 0)
        Gdx.input.inputProcessor.touchUp(x, y, 0, 0)
    }

    override fun render() {
        super.render()
        frames++
        if (battle) {
            val input = Gdx.input.inputProcessor
            when (frames) {
                2 -> startBattle(mission)
                30, 32 -> tap(340, 545)
                40 -> tap(1180, 60)
                // Drag the camera up and right after the squad.
                50 -> input.touchDown(1000, 200, 0, 0)
                in 51..60 -> input.touchDragged(1000 - (frames - 50) * 40, 200 + (frames - 50) * 30, 0)
                61 -> input.touchUp(600, 500, 0, 0)
            }
        }
        if (scene == "missions" && frames == 30) tap(1130, 38)
        if (scene == "select") {
            // One soldier selected, standing still: shows its field of fire.
            when (frames) {
                2 -> startBattle(mission)
                30 -> tap(System.getenv("WARNATION_TAP_X")?.toInt() ?: 340, System.getenv("WARNATION_TAP_Y")?.toInt() ?: 545)
            }
        }
        if (frames == captureFrame) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(path), pixmap, 0, true)
            pixmap.dispose()
            Gdx.app.exit()
        }
    }
}
