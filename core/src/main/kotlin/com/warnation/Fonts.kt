package com.warnation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fonts rendered at the device's pixel density so text stays crisp on phones.
 * Black Ops One and Rajdhani, both SIL Open Font License (see assets/fonts).
 */
class Fonts : Disposable {
    /** Screen pixels per density-independent pixel (at least 1, so desktop isn't tiny). */
    val density = max(1f, Gdx.graphics.density)

    val title: BitmapFont
    val heading: BitmapFont
    val body: BitmapFont
    val small: BitmapFont

    /** Fixed-size font for labels drawn in the game world, which zooms with the camera. */
    val world: BitmapFont

    init {
        val stencil = FreeTypeFontGenerator(Gdx.files.internal("fonts/BlackOpsOne-Regular.ttf"))
        val semiBold = FreeTypeFontGenerator(Gdx.files.internal("fonts/Rajdhani-SemiBold.ttf"))
        val bold = FreeTypeFontGenerator(Gdx.files.internal("fonts/Rajdhani-Bold.ttf"))
        title = generate(stencil, px(48f))
        heading = generate(stencil, px(22f))
        body = generate(semiBold, px(18f))
        small = generate(semiBold, px(15f))
        world = generate(bold, 18)
        stencil.dispose()
        semiBold.dispose()
        bold.dispose()
    }

    fun dp(value: Float) = value * density

    private fun px(dpSize: Float): Int = (dpSize * density).roundToInt()

    private fun generate(generator: FreeTypeFontGenerator, pixels: Int): BitmapFont {
        val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            size = pixels
            minFilter = Texture.TextureFilter.Linear
            magFilter = Texture.TextureFilter.Linear
            characters = FreeTypeFontGenerator.DEFAULT_CHARS + "·–"
        }
        return generator.generateFont(parameter)
    }

    override fun dispose() {
        title.dispose()
        heading.dispose()
        body.dispose()
        small.dispose()
        world.dispose()
    }
}
