// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Oyun görüntüsünün alt kısmındaki İngilizce altyazıyı okur (OCR),
 * Türkçeye çevirir ve ekranın üstünde gösterir.
 */
class SubtitleTranslator(
	private val surfaceView: SurfaceView,
	private val subtitleView: TextView)
{
	companion object
	{
		private const val TAG = "SubtitleTranslator"
		private const val CAPTURE_INTERVAL_MS = 300L
		private const val HIDE_AFTER_MS = 1500L

		// Altyazı bölgesi en az bu genişlikte okunur (küçükse büyütülür)
		private const val MIN_OCR_W = 1600

		// Altyazının arandığı bölge (oran olarak)
		private const val REGION_TOP = 0.66f
		private const val REGION_BOTTOM = 0.985f
		private const val REGION_LEFT = 0.17f   // sol alttaki mini haritayı atla
		private const val REGION_RIGHT = 0.90f
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var copyThread: HandlerThread? = null
	private var copyHandler: Handler? = null

	private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator: Translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build())

	private var running = false
	private var busy = false
	private var useFullFrame = false   // bölge kopyalama desteklenmezse tüm kareyi kopyala
	private var modelReady = false

	private var lastSource = ""        // en son çevrilen metin (normalize)
	private var lastSeenTime = 0L
	private val cache = LinkedHashMap<String, String>(64, 0.75f, true)
	private val gptCache = HashMap<String, String>()
	private val recentLines = ArrayDeque<String>()    // bağlam için son altyazılar
	private val gptExecutor = Executors.newSingleThreadExecutor()
	private var gptErrorShown = false

	fun start()
	{
		if(running)
			return
		running = true
		copyThread = HandlerThread("subtitle-copy").also { it.start() }
		copyHandler = Handler(copyThread!!.looper)

		if(!modelReady)
		{
			show("Türkçe çeviri modeli hazırlanıyor…")
			translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
				.addOnSuccessListener {
					modelReady = true
					show("Türkçe altyazı hazır ✓")
					mainHandler.postDelayed({ if(lastSource.isEmpty()) hide() }, 2500)
				}
				.addOnFailureListener {
					Log.e(TAG, "Model indirilemedi", it)
					show("Çeviri modeli indirilemedi. TV'nin internet bağlantısını kontrol edin.")
				}
		}
		mainHandler.postDelayed(tick, CAPTURE_INTERVAL_MS)
	}

	fun stop()
	{
		running = false
		mainHandler.removeCallbacks(tick)
		copyThread?.quitSafely()
		copyThread = null
		copyHandler = null
		busy = false
	}

	fun close()
	{
		stop()
		recognizer.close()
		translator.close()
		gptExecutor.shutdownNow()
	}

	private val tick = object : Runnable
	{
		override fun run()
		{
			if(!running)
				return
			mainHandler.postDelayed(this, CAPTURE_INTERVAL_MS)

			if(subtitleView.visibility == View.VISIBLE && lastSource.isNotEmpty()
				&& SystemClock.uptimeMillis() - lastSeenTime > HIDE_AFTER_MS)
			{
				lastSource = ""
				hide()
			}

			if(busy || !modelReady)
				return
			val surface = surfaceView.holder.surface
			if(surface == null || !surface.isValid || surfaceView.width == 0)
				return
			val handler = copyHandler ?: return

			busy = true
			val frame = surfaceView.holder.surfaceFrame
			val sw = if(frame.width() > 0) frame.width() else surfaceView.width
			val sh = if(frame.height() > 0) frame.height() else surfaceView.height
			val src = Rect(0, (sh * REGION_TOP).toInt(), sw, (sh * REGION_BOTTOM).toInt())
			val scale = if(src.width() < MIN_OCR_W) MIN_OCR_W.toFloat() / src.width() else 1f
			val full = useFullFrame
			val bitmap = if(full)
				Bitmap.createBitmap((sw * scale).toInt(), (sh * scale).toInt(), Bitmap.Config.ARGB_8888)
			else
				Bitmap.createBitmap((src.width() * scale).toInt(), (src.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
			try
			{
				val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
					if(result == PixelCopy.SUCCESS)
					{
						if(full)
						{
							val top = (bitmap.height * REGION_TOP).toInt()
							val bottom = (bitmap.height * REGION_BOTTOM).toInt()
							val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
							bitmap.recycle()
							recognize(crop)
						}
						else
							recognize(bitmap)
					}
					else
					{
						if(!full)
							useFullFrame = true
						bitmap.recycle()
						busy = false
					}
				}
				if(full)
					PixelCopy.request(surfaceView, bitmap, listener, handler)
				else
					PixelCopy.request(surfaceView, src, bitmap, listener, handler)
			}
			catch(e: Exception)
			{
				Log.w(TAG, "PixelCopy failed", e)
				bitmap.recycle()
				busy = false
			}
		}
	}

	private fun recognize(crop: Bitmap)
	{

		recognizer.process(InputImage.fromBitmap(crop, 0))
			.addOnSuccessListener { text ->
				val w = crop.width
				crop.recycle()
				busy = false
				onText(extractSubtitle(text, w))
			}
			.addOnFailureListener {
				crop.recycle()
				busy = false
			}
	}

	private fun extractSubtitle(text: Text, width: Int): String
	{
		val minX = width * REGION_LEFT
		val maxX = width * REGION_RIGHT
		val lines = text.textBlocks
			.flatMap { it.lines }
			.filter { line ->
				val box: Rect = line.boundingBox ?: return@filter false
				val cx = box.exactCenterX()
				cx in minX..maxX && line.text.count { it.isLetter() } >= 3
			}
			.sortedBy { it.boundingBox?.top ?: 0 }
		return lines.joinToString(" ") { it.text.trim() }
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

	private fun onText(raw: String)
	{
		if(!running)
			return
		val norm = normalize(raw)
		if(norm.length < 4)
		{
			return
		}

		// Aynı altyazı hâlâ ekranda
		if(similar(norm, lastSource))
		{
			lastSeenTime = SystemClock.uptimeMillis()
			return
		}

		// Altyazı yazılırken/silinirken kısmi okunan metni ele: önceki metnin bir parçasıysa bekle
		if(lastSource.isNotEmpty() && lastSource.contains(norm) && SystemClock.uptimeMillis() - lastSeenTime < 600)
			return

		lastSource = norm
		lastSeenTime = SystemClock.uptimeMillis()
		gptCache[norm]?.let { show(it); return }
		requestGpt(raw, norm)
		cache[norm]?.let { show(it); return }

		translator.translate(raw)
			.addOnSuccessListener { tr ->
				cache[norm] = tr
				if(cache.size > 300)
					cache.remove(cache.keys.first())
				if(lastSource == norm && !gptCache.containsKey(norm))
					show(tr)
			}
			.addOnFailureListener { Log.w(TAG, "Çeviri hatası", it) }
	}

	/** API anahtarı girildiyse ChatGPT ile daha doğal çeviri alır; gelince cihaz çevirisinin yerine geçer. */
	private fun requestGpt(raw: String, norm: String)
	{
		val prefs = PreferenceManager.getDefaultSharedPreferences(surfaceView.context)
		val key = prefs.getString("openai_api_key", null)?.trim().orEmpty()
		if(key.isEmpty())
			return
		val model = prefs.getString("openai_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: "gpt-5.4-nano"
		val context = recentLines.joinToString("\n")
		recentLines.addLast(raw)
		while(recentLines.size > 4)
			recentLines.removeFirst()

		gptExecutor.execute {
			try
			{
				val system = "Sen bir oyun altyazı çevirmenisin. Oyun: GTA (Grand Theft Auto). " +
					"Gelen İngilizce altyazıyı doğal, akıcı, konuşma dilinde Türkçeye çevir. Argo ve küfürleri anlamına uygun Türkçe karşılıklarıyla çevir. " +
					"Metin ekrandan okunduğu için küçük harf hataları olabilir; anlamdan düzelt. Karakter ve yer adlarını çevirme. " +
					"Sadece çeviriyi yaz, açıklama ekleme."
				val user = (if(context.isNotEmpty()) "Önceki altyazılar (sadece bağlam için, çevirme):\n$context\n\n" else "") +
					"Çevrilecek altyazı:\n$raw"
				val body = JSONObject()
					.put("model", model)
					.put("messages", JSONArray()
						.put(JSONObject().put("role", "system").put("content", system))
						.put(JSONObject().put("role", "user").put("content", user)))
				val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
				conn.requestMethod = "POST"
				conn.connectTimeout = 5000
				conn.readTimeout = 15000
				conn.doOutput = true
				conn.setRequestProperty("Authorization", "Bearer $key")
				conn.setRequestProperty("Content-Type", "application/json")
				conn.outputStream.use { it.write(body.toString().toByteArray()) }
				val code = conn.responseCode
				if(code !in 200..299)
				{
					val err = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
					throw Exception("HTTP $code $err")
				}
				val json = JSONObject(conn.inputStream.bufferedReader().readText())
				val tr = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
				if(tr.isEmpty())
					return@execute
				mainHandler.post {
					gptCache[norm] = tr
					if(lastSource == norm)
						show(tr)
				}
			}
			catch(e: Exception)
			{
				Log.w(TAG, "ChatGPT hatası", e)
				mainHandler.post {
					if(!gptErrorShown)
					{
						gptErrorShown = true
						Toast.makeText(surfaceView.context, "ChatGPT çevirisi çalışmadı: ${e.message}", Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}

	/** Küçük OCR farklarını (1-2 harf) aynı metin say. */
	private fun similar(a: String, b: String): Boolean
	{
		if(a.isEmpty() || b.isEmpty())
			return false
		if(a == b)
			return true
		val maxLen = maxOf(a.length, b.length)
		if(kotlin.math.abs(a.length - b.length) > maxLen / 8 + 1)
			return false
		return levenshtein(a, b) <= maxLen / 10 + 1
	}

	private fun levenshtein(a: String, b: String): Int
	{
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for(i in 1..a.length)
		{
			cur[0] = i
			for(j in 1..b.length)
			{
				val cost = if(a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
			}
			val t = prev; prev = cur; cur = t
		}
		return prev[b.length]
	}

	private fun show(s: String)
	{
		subtitleView.text = s
		subtitleView.visibility = View.VISIBLE
	}

	private fun hide()
	{
		subtitleView.visibility = View.GONE
	}
}
