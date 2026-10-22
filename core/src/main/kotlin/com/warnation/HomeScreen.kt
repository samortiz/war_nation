package com.warnation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TiledDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport

/** Title screen: start the campaign or a skirmish, pick a mission and choose which soldiers make up the squad. */
class HomeScreen(private val game: WarNationGame) : ScreenAdapter() {
    private val assets = game.assets
    private val fonts = game.fonts
    private val squad = game.squad
    private val campaign = game.campaign
    private val stage = Stage(ScreenViewport())

    private val primaryButton = buttonStyle(fonts.heading, Color(0.33f, 0.55f, 0.22f, 1f), Color(0.22f, 0.4f, 0.15f, 1f))
    private val stepButton = buttonStyle(fonts.heading, Color(0.25f, 0.3f, 0.22f, 1f), Color(0.16f, 0.2f, 0.14f, 1f))
    private val tabButton = buttonStyle(fonts.heading, Color(0.16f, 0.2f, 0.14f, 1f), Color(0.25f, 0.3f, 0.22f, 1f)).apply {
        checked = solid(Color(0.33f, 0.55f, 0.22f, 1f))
    }

    private val countLabels = mutableMapOf<SoldierType, Label>()
    private val addButtons = mutableMapOf<SoldierType, TextButton>()
    private val removeButtons = mutableMapOf<SoldierType, TextButton>()
    private val sizeLabel = label("", fonts.heading, ACCENT)
    private val campaignButton = TextButton("CAMPAIGN", primaryButton)
    private val skirmishButton = TextButton("SKIRMISH", stepButton)
    private val squadTab = TextButton("SQUAD", tabButton).apply { setProgrammaticChangeEvents(false) }
    private val missionsTab = TextButton("MISSIONS", tabButton).apply { setProgrammaticChangeEvents(false) }
    private val panel = ScrollPane(null).apply { setScrollingDisabled(true, false) }
    private val squadPanel = squadPanel()
    private val missionsPanel = missionsPanel()

    init {
        val background = TiledDrawable(assets.ground[0]).apply { scale = fonts.density }
        val root = Stack(
            Image(background),
            Image(solid(Color(0.04f, 0.06f, 0.04f, 0.6f))),
            Table().apply {
                pad(dp(16f))
                add(titleColumn()).expand()
                add(Table().apply {
                    add(squadTab).growX().height(dp(44f))
                    add(missionsTab).growX().height(dp(44f)).row()
                    // Scrolls when the screen is too short to show the whole panel.
                    add(panel).colspan(2).grow()
                }).padLeft(dp(16f)).growY()
            },
        )
        root.setFillParent(true)
        stage.addActor(root)

        campaignButton.onChange { game.startBattle(campaign.nextMission) }
        skirmishButton.onChange { game.startBattle() }
        squadTab.onChange { showPanel(squad = true) }
        missionsTab.onChange { showPanel(squad = false) }
        showPanel(squad = true)
        refresh()
    }

    private fun titleColumn() = Table().apply {
        add(label("WAR NATION", fonts.title, ACCENT)).row()
        add(label("Top-down squad tactics", fonts.body, MUTED)).padBottom(dp(28f)).row()
        add(campaignButton).width(dp(240f)).height(dp(60f)).row()
        val next = if (campaign.finished) "All missions complete"
        else "Mission ${campaign.nextMission + 1}: ${Campaign.MISSIONS[campaign.nextMission].title}"
        add(label(next, fonts.small, MUTED)).padTop(dp(6f)).padBottom(dp(14f)).row()
        add(skirmishButton).width(dp(240f)).height(dp(48f)).row()
        val record = "Victories ${squad.victories}   ·   Defeats ${squad.defeats}"
        add(label(record, fonts.small, MUTED)).padTop(dp(16f))
    }

    private fun squadPanel() = Table().apply {
        background = solid(Color(0f, 0f, 0f, 0.5f))
        pad(dp(12f))
        top()
        defaults().left()

        add(Table().apply {
            add(label("SQUAD", fonts.heading, Color.WHITE)).expandX().left()
            add(sizeLabel)
        }).growX().padBottom(dp(8f)).row()

        for (type in SoldierType.entries) add(soldierRow(type)).growX().padTop(dp(6f)).row()
    }

    /** Every mission, playable once the one before it is won. */
    private fun missionsPanel() = Table().apply {
        background = solid(Color(0f, 0f, 0f, 0.5f))
        pad(dp(12f))
        top()
        defaults().left()
        add(label("CAMPAIGN  ${campaign.completed} / ${Campaign.MISSIONS.size}", fonts.heading, Color.WHITE)).padBottom(dp(8f)).row()

        for ((index, mission) in Campaign.MISSIONS.withIndex()) {
            val unlocked = campaign.isUnlocked(index)
            add(Table().apply {
                val status = when {
                    campaign.isCompleted(index) -> "WON"
                    unlocked -> "NEXT"
                    else -> "LOCKED"
                }
                add(Table().apply {
                    defaults().left()
                    add(label("${index + 1}. ${mission.title}", fonts.body, if (unlocked) Color.WHITE else MUTED)).row()
                    val briefing = if (unlocked) mission.briefing else "Win the previous mission to unlock."
                    add(label(briefing, fonts.small, MUTED).apply { wrap = true }).width(dp(300f))
                }).expandX().left()
                if (unlocked) {
                    add(label(status, fonts.small, if (status == "WON") STATS else ACCENT)).padRight(dp(8f))
                    add(TextButton("PLAY", stepButton).onChange { game.startBattle(index) }).width(dp(96f)).height(dp(40f))
                } else {
                    add(label(status, fonts.small, MUTED)).width(dp(96f)).height(dp(40f)).apply { actor.setAlignment(Align.center) }
                }
            }).growX().padTop(dp(8f)).row()
        }
    }

    private fun showPanel(squad: Boolean) {
        squadTab.isChecked = squad
        missionsTab.isChecked = !squad
        panel.actor = if (squad) squadPanel else missionsPanel
        panel.scrollY = 0f
    }

    private fun soldierRow(type: SoldierType) = Table().apply {
        val portrait = Image(assets.sprite(Team.PLAYER, type)).apply { setScaling(Scaling.fit) }
        add(portrait).size(dp(44f)).padRight(dp(10f))

        val shotsPerSecond = "%.1f".format(1f / type.fireInterval)
        val stats = "HP ${type.maxHp.toInt()}  ·  RNG ${type.range.toInt()}  ·  DMG ${type.damage.toInt()}  ·  " +
            "$shotsPerSecond/s  ·  VIEW ${type.fieldOfView.toInt()}°"
        add(Table().apply {
            defaults().left()
            add(label(type.displayName, fonts.body, Color.WHITE)).row()
            add(label(type.role, fonts.small, MUTED)).row()
            add(label(stats, fonts.small, STATS))
        }).expandX().left()

        val remove = TextButton("-", stepButton).onChange { squad.remove(type); refresh() }
        val add = TextButton("+", stepButton).onChange { squad.add(type); refresh() }
        val count = label("", fonts.heading, Color.WHITE).apply { setAlignment(Align.center) }
        add(remove).size(dp(40f))
        add(count).width(dp(36f))
        add(add).size(dp(40f))
        removeButtons[type] = remove
        addButtons[type] = add
        countLabels[type] = count
    }

    private fun refresh() {
        sizeLabel.setText("${squad.size} / ${Squad.MAX_SIZE}")
        for (type in SoldierType.entries) {
            countLabels.getValue(type).setText(squad.count(type).toString())
            addButtons.getValue(type).isDisabled = !squad.canAdd()
            removeButtons.getValue(type).isDisabled = !squad.canRemove(type)
        }
        campaignButton.isDisabled = squad.size == 0
        skirmishButton.isDisabled = squad.size == 0
    }

    override fun show() {
        Gdx.input.inputProcessor = InputMultiplexer(stage, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode != Input.Keys.BACK && keycode != Input.Keys.ESCAPE) return false
                Gdx.app.exit()
                return true
            }
        })
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() = stage.dispose()

    private fun dp(value: Float) = fonts.dp(value)

    private fun solid(color: Color): Drawable = TextureRegionDrawable(assets.pixel).tint(color)

    private fun label(text: String, font: BitmapFont, color: Color) = Label(text, Label.LabelStyle(font, color))

    private fun buttonStyle(font: BitmapFont, up: Color, down: Color) =
        TextButton.TextButtonStyle(solid(up), solid(down), null, font).apply {
            disabled = solid(Color(0.2f, 0.2f, 0.2f, 0.6f))
            disabledFontColor = Color(1f, 1f, 1f, 0.3f)
        }

    private fun <T : Actor> T.onChange(action: () -> Unit): T = apply {
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = action()
        })
    }

    companion object {
        private val ACCENT = Color(0.91f, 0.83f, 0.42f, 1f)
        private val MUTED = Color(0.75f, 0.78f, 0.72f, 1f)
        private val STATS = Color(0.62f, 0.85f, 0.6f, 1f)
    }
}
