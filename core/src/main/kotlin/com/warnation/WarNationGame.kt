package com.warnation

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen

open class WarNationGame : Game() {
    lateinit var assets: Assets
        private set
    lateinit var fonts: Fonts
        private set
    lateinit var squad: Squad
        private set
    lateinit var campaign: Campaign
        private set

    override fun create() {
        assets = Assets()
        fonts = Fonts()
        val prefs = Gdx.app.getPreferences("war_nation")
        squad = Squad(prefs)
        campaign = Campaign(prefs)
        // Android's back button returns to the home screen instead of closing the app.
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        showHome()
    }

    fun showHome() = switchTo { HomeScreen(this) }

    /** Starts campaign mission [mission] (an index into [Campaign.MISSIONS]), or a skirmish if null. */
    fun startBattle(mission: Int? = null) = switchTo { GameScreen(this, squad.members(), mission) }

    /** Switches on the next frame, so a screen can safely navigate away from inside its own input handling. */
    private fun switchTo(next: () -> Screen) = Gdx.app.postRunnable {
        val previous = screen
        setScreen(next())
        previous?.dispose()
    }

    override fun dispose() {
        screen?.dispose()
        fonts.dispose()
        assets.dispose()
    }
}
