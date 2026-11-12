package com.kejing.tangramwood.ui.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.kejing.tangramwood.domain.Layout
import com.kejing.tangramwood.platform.TAG
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canvas textures (design budget: 木纹 Canvas 512² × 3, 立牌 512 × 128): procedural wood grain for maple / cherry /
 * walnut, the title plate and the three tab faces. Text and icons are tested with the 「F↑」 image first (D10).
 */
object BoardTextures {
    val MAPLE = Color.rgb(0xE8, 0xC9, 0x9A)
    val CHERRY = Color.rgb(0xD9, 0x8A, 0x5F)
    val WALNUT = Color.rgb(0x5E, 0x41, 0x30)
    val WALNUT_DARK = Color.rgb(0x4A, 0x33, 0x25)
    val TRAY = Color.rgb(0x6B, 0x4A, 0x34)

    /** Straight-grain wood around [base]: soft growth rings plus fine streaks (no image assets). */
    fun wood(base: Int, seed: Int, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rng = Random(seed)
        val r0 = Color.red(base); val g0 = Color.green(base); val b0 = Color.blue(base)
        val ph = FloatArray(6) { rng.nextFloat() * 6.28f }
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / size; val fy = y.toFloat() / size
            val ring = sin(fx * 22f + sin(fy * 4f + ph[0]) * 1.4f + sin(fy * 11f + ph[1]) * 0.25f + ph[2]) * 0.5f + 0.5f
            val streak = sin(fx * 260f + sin(fy * 7f + ph[3]) * 3f + ph[4]) * 0.5f + 0.5f
            val k = 0.90f + 0.08f * ring * ring + 0.04f * streak - 0.05f * (if (streak > 0.95f) 1f else 0f)
            px[y * size + x] = Color.rgb((r0 * k).toInt().coerceIn(0, 255), (g0 * k).toInt().coerceIn(0, 255), (b0 * k).toInt().coerceIn(0, 255))
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /** A soft dark disc for the contact shadow under the solved figure. */
    fun softShadow(size: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(size / 2f, size / 2f, size / 2f, intArrayOf(Color.argb(140, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        return bmp
    }

    /** "F↑" orientation test (profile D10): the F must read upright from the user with the arrow pointing up. */
    fun orientationTest(w: Int = 256, h: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = h * 0.6f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("F↑", w / 2f, h * 0.7f, p)
        return bmp
    }

    val PLATE_FACE = Color.rgb(0xF6, 0xEC, 0xD8)
    val PLATE_INK = Color.rgb(0x2A, 0x1E, 0x14)
    val PLATE_EDGE = Color.rgb(0x6B, 0x4A, 0x2E)

    private fun platePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PLATE_INK; textSize = 104f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun plateTextWidth(text: String): Float = platePaint().measureText(text)

    /**
     * The title plate (design spatialLayout.titlePlate): 1280 x 256 px for 0.30 x 0.06 m, light wood face, ink text; a
     * second smaller line (S17 「点一下木片，面板就回来。」) when [line2] is set. A line wider than the text width logs
     * OVERFLOW and is drawn smaller (never cut).
     */
    fun plate(text: String, line2: String? = null, test: Boolean = false): Bitmap {
        if (test) return orientationTest(Layout.PLATE_PX_W, Layout.PLATE_PX_H)
        val bmp = Bitmap.createBitmap(Layout.PLATE_PX_W, Layout.PLATE_PX_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(0f, 0f, Layout.PLATE_PX_W.toFloat(), Layout.PLATE_PX_H.toFloat())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_EDGE }
        c.drawRect(r, bg)
        bg.color = PLATE_FACE
        c.drawRoundRect(RectF(12f, 12f, r.right - 12f, r.bottom - 12f), 24f, 24f, bg)
        val p = platePaint()
        if (line2 != null) p.textSize = 84f
        val w = p.measureText(text)
        if (w > Layout.PLATE_TEXT_PX) {
            Log.w(TAG, "OVERFLOW plate/text \"$text\" ${w.toInt()} px > ${Layout.PLATE_TEXT_PX}")
            p.textSize *= Layout.PLATE_TEXT_PX / w
        }
        val fm = p.fontMetrics
        if (line2 == null) {
            c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, p)
        } else {
            c.drawText(text, r.centerX(), r.height() * 0.42f - (fm.ascent + fm.descent) / 2, p)
            val q = platePaint().apply { textSize = 58f; typeface = Typeface.DEFAULT; color = Color.rgb(0x4A, 0x36, 0x26) }
            val w2 = q.measureText(line2)
            if (w2 > Layout.PLATE_TEXT_PX) { Log.w(TAG, "OVERFLOW plate/line2 \"$line2\""); q.textSize *= Layout.PLATE_TEXT_PX / w2 }
            val f2 = q.fontMetrics
            c.drawText(line2, r.centerX(), r.height() * 0.78f - (f2.ascent + f2.descent) / 2, q)
        }
        return bmp
    }

    /**
     * One tab face (design input.tabs: 「图标 + 字」 on a maple tab): 512² px for the 8 × 8 cm tab, the Material Symbols
     * icon (rotate_left / rotate_right / flip, D23) on top, the word below, dark ink on maple (≈ 9:1).
     */
    fun tabFace(context: Context, icon: Int, label: String, test: Boolean = false): Bitmap {
        if (test) return orientationTest(512, 512)
        val s = 512
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9C, 0x74, 0x4E) }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bg)
        bg.color = Color.rgb(0xF2, 0xDE, 0xBC)
        c.drawRoundRect(RectF(14f, 14f, s - 14f, s - 14f), 48f, 48f, bg)
        val ink = Color.rgb(0x2A, 0x1E, 0x14)
        runCatching {
            val d = context.getDrawable(icon)!!.mutate()
            d.setTint(ink)
            d.setBounds(136, 60, 376, 300)
            d.draw(c)
        }.onFailure { Log.w(TAG, "tab icon: $it") }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 120f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val w = p.measureText(label)
        if (w > 460f) { Log.w(TAG, "OVERFLOW tab/label \"$label\""); p.textSize *= 460f / w }
        c.drawText(label, s / 2f, 438f, p)
        return bmp
    }
}
