package com.alphaprime.tv.data

import android.content.Context

object Prefs {
    private const val NAME = "alphaprime"
    private const val KEY_TYPE   = "conn_type"
    private const val KEY_M3U    = "m3u_url"
    private const val KEY_SERVER = "xtream_server"
    private const val KEY_USER   = "xtream_user"
    private const val KEY_PASS   = "xtream_pass"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getType(ctx: Context)   = sp(ctx).getString(KEY_TYPE,   "m3u")   ?: "m3u"
    fun getM3U(ctx: Context)    = sp(ctx).getString(KEY_M3U,    "")      ?: ""
    fun getServer(ctx: Context) = sp(ctx).getString(KEY_SERVER, "")      ?: ""
    fun getUser(ctx: Context)   = sp(ctx).getString(KEY_USER,   "")      ?: ""
    fun getPass(ctx: Context)   = sp(ctx).getString(KEY_PASS,   "")      ?: ""

    fun isConfigured(ctx: Context) = getM3U(ctx).isNotBlank() || getServer(ctx).isNotBlank()

    fun saveM3U(ctx: Context, url: String) =
        sp(ctx).edit().putString(KEY_TYPE, "m3u").putString(KEY_M3U, url).apply()

    fun saveXtream(ctx: Context, server: String, user: String, pass: String) =
        sp(ctx).edit()
            .putString(KEY_TYPE,   "xtream")
            .putString(KEY_SERVER, server)
            .putString(KEY_USER,   user)
            .putString(KEY_PASS,   pass)
            .apply()
}
