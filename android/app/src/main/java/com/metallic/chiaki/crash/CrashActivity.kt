// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.crash

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity()
{
	companion object { const val EXTRA_TEXT = "text" }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val tv = TextView(this)
		tv.setTextColor(Color.WHITE)
		tv.setBackgroundColor(Color.BLACK)
		tv.textSize = 13f
		tv.setPadding(40, 30, 40, 30)
		tv.text = "UYGULAMA HATASI — bu ekranın fotoğrafını çekin\n\n" + (intent.getStringExtra(EXTRA_TEXT) ?: "?")
		tv.isFocusable = true
		val sv = ScrollView(this)
		sv.addView(tv)
		setContentView(sv)
	}
}
