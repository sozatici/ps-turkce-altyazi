// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.crash

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** Çökme olursa hatayı ekranda gösterir (TV'de log okumak zor olduğu için). */
class CrashApp : Application()
{
	override fun onCreate()
	{
		super.onCreate()
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, e ->
			try
			{
				val sw = StringWriter()
				e.printStackTrace(PrintWriter(sw))
				val text = "Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / ${Build.SUPPORTED_ABIS.joinToString()}\n\n$sw"
				startActivity(Intent(this, CrashActivity::class.java)
					.putExtra(CrashActivity.EXTRA_TEXT, text)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
				Process.killProcess(Process.myPid())
				exitProcess(10)
			}
			catch(t: Throwable)
			{
				previous?.uncaughtException(thread, e)
			}
		}
	}
}
