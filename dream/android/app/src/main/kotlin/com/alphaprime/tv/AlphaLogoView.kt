package com.alphaprime.tv

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class AlphaLogoView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val paintGold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintPurple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(100, 100, 30, 200)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = w * 0.11f

        paintGlow.strokeWidth = sw * 2.8f
        paintGlow.maskFilter = BlurMaskFilter(sw * 1.8f, BlurMaskFilter.Blur.NORMAL)

        // Glow
        canvas.drawPath(Path().apply {
            moveTo(w * 0.10f, h * 0.87f)
            lineTo(w * 0.44f, h * 0.10f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.44f, h * 0.87f)
        }, paintGlow)

        paintGold.strokeWidth = sw
        paintGold.shader = LinearGradient(
            w * 0.10f, h * 0.87f, w * 0.63f, h * 0.40f,
            intArrayOf(0xFFD4A843.toInt(), 0xFFB8860B.toInt()),
            null, Shader.TileMode.CLAMP
        )
        // Lado dourado: base-esq → pico → meio-dir
        canvas.drawPath(Path().apply {
            moveTo(w * 0.10f, h * 0.87f)
            lineTo(w * 0.44f, h * 0.10f)
            lineTo(w * 0.63f, h * 0.40f)
        }, paintGold)

        paintPurple.strokeWidth = sw
        paintPurple.shader = LinearGradient(
            w * 0.44f, h * 0.10f, w * 0.44f, h * 0.87f,
            intArrayOf(0xFF9B59B6.toInt(), 0xFF6A0DAD.toInt()),
            null, Shader.TileMode.CLAMP
        )
        // Lado roxo: pico → ponta-dir → base-dir
        canvas.drawPath(Path().apply {
            moveTo(w * 0.44f, h * 0.10f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.44f, h * 0.87f)
        }, paintPurple)

        paintCross.strokeWidth = sw * 0.8f
        paintCross.shader = LinearGradient(
            w * 0.27f, h * 0.56f, w * 0.63f, h * 0.56f,
            intArrayOf(0xFFD4A843.toInt(), 0xFF7B35C4.toInt()),
            null, Shader.TileMode.CLAMP
        )
        // Crossbar do A
        canvas.drawLine(w * 0.27f, h * 0.56f, w * 0.63f, h * 0.56f, paintCross)
    }
}
