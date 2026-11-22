// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSN hesabıyla giriş yapıp PSN Account ID'yi (Base64) otomatik bulur.
 * (chiaki-ng masaüstü sürümündeki yöntemle aynı.)
 */
class PsnLoginActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
			"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
			"&redirect_uri=$REDIRECT&scope=" + Uri.encode(SCOPE) +
			"&request_locale=tr_TR&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal"
	}

	private var handled = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "PlayStation ile giriş"

		CookieManager.getInstance().removeAllCookies(null)
		val webView = WebView(this)
		setContentView(webView)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
				= checkRedirect(request.url.toString())

			override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?)
			{
				if(checkRedirect(url))
					view.stopLoading()
			}
		}
		webView.loadUrl(LOGIN_URL)
	}

	private fun checkRedirect(url: String): Boolean
	{
		if(!url.startsWith(REDIRECT))
			return false
		if(handled)
			return true
		handled = true
		val code = Uri.parse(url).getQueryParameter("code")
		if(code == null)
		{
			fail("Giriş kodu alınamadı")
			return true
		}
		Toast.makeText(this, "Account ID alınıyor…", Toast.LENGTH_SHORT).show()
		Thread {
			try
			{
				val id = fetchAccountId(code)
				runOnUiThread {
					setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, id))
					finish()
				}
			}
			catch(e: Exception)
			{
				runOnUiThread { fail(e.message ?: e.toString()) }
			}
		}.start()
		return true
	}

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	private fun fetchAccountId(code: String): String
	{
		val body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") +
			"&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
			"&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
		val tokenConn = URL(TOKEN_URL).openConnection() as HttpURLConnection
		tokenConn.requestMethod = "POST"
		tokenConn.doOutput = true
		tokenConn.setRequestProperty("Authorization", basicAuth())
		tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		OutputStreamWriter(tokenConn.outputStream).use { it.write(body) }
		if(tokenConn.responseCode !in 200..299)
			throw Exception("Token hatası: HTTP ${tokenConn.responseCode}")
		val token = JSONObject(tokenConn.inputStream.bufferedReader().readText()).getString("access_token")

		val infoConn = URL("$TOKEN_URL/$token").openConnection() as HttpURLConnection
		infoConn.setRequestProperty("Authorization", basicAuth())
		if(infoConn.responseCode !in 200..299)
			throw Exception("Hesap bilgisi hatası: HTTP ${infoConn.responseCode}")
		val userId = JSONObject(infoConn.inputStream.bufferedReader().readText()).getString("user_id").toLong()
		val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId).array()
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}

	private fun fail(msg: String)
	{
		Toast.makeText(this, "PSN girişi başarısız: $msg", Toast.LENGTH_LONG).show()
		setResult(RESULT_CANCELED)
		finish()
	}
}
