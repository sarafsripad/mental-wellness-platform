package org.mentalwellness.app.secure

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.mentalwellness.app.BuildConfig

/**
 * ASHA Care's native module (ADR-017). Synthetic data only.
 *
 * Each worker's offline store is an SQLCipher database whose random 256-bit key is wrapped twice:
 * first by a key derived from the worker's PIN (PBKDF2-SHA-256), then by an AES key held in the
 * Android Keystore, so the stored blob is useless off this device and without the PIN. After
 * five wrong PINs the store is deleted. Stores live in no-backup app storage.
 *
 * Audio is 16 kHz mono 16-bit PCM WAV, the contract's WAV_PCM_S16LE. The microphone is used only in
 * builds made with ashaMicrophoneEnabled=true, which needs privacy-lead approval. Otherwise the app
 * records a generated synthetic voice.
 */
class AshaSecureModule(private val context: ReactApplicationContext) : NativeAshaSecureSpec(context) {

  private val random = SecureRandom()
  private val prefs = context.getSharedPreferences("asha_offline", Context.MODE_PRIVATE)
  @Volatile private var recorder: Recorder? = null

  override fun getName() = NAME

  override fun getTypedExportedConstants(): Map<String, Any> =
      mapOf("microphoneEnabled" to BuildConfig.ASHA_MICROPHONE_ENABLED, "storeDir" to storeDir().absolutePath)

  override fun ownerOf(subject: String, promise: Promise) {
    promise.resolve(hex(MessageDigest.getInstance("SHA-256").digest("asha-offline:$subject".toByteArray())))
  }

  override fun randomHex(bytes: Double): String = hex(ByteArray(bytes.toInt()).also(random::nextBytes))

  override fun uuid(): String = UUID.randomUUID().toString() // backed by SecureRandom

  override fun listStores(promise: Promise) {
    val list = Arguments.createArray()
    owners().forEach { owner ->
      list.pushMap(Arguments.createMap().apply {
        putString("owner", owner)
        putString("createdAt", prefs.getString("$owner.created", ""))
        putInt("attemptsLeft", MAX_ATTEMPTS - prefs.getInt("$owner.failed", 0))
      })
    }
    promise.resolve(list)
  }

  override fun createStore(owner: String, pin: String, promise: Promise) {
    if (!PIN.matches(pin)) return promise.reject("BAD_PIN", "The PIN must be 6 to 12 digits.")
    if (owners().contains(owner)) return promise.reject("EXISTS", "Offline data already exists for this worker.")
    val dbKey = ByteArray(32).also(random::nextBytes)
    val salt = ByteArray(16).also(random::nextBytes)
    val inner = seal(pinKey(pin, salt), dbKey)
    val outer = seal(deviceKey(), inner)
    prefs.edit()
        .putString("$owner.salt", b64(salt))
        .putString("$owner.wrapped", b64(outer))
        .putInt("$owner.failed", 0)
        .putString("$owner.created", Instant.now().toString())
        .putStringSet("owners", owners() + owner)
        .commit()
    promise.resolve(hex(dbKey))
  }

  override fun unlockStore(owner: String, pin: String, promise: Promise) {
    val salt = prefs.getString("$owner.salt", null)
    val wrapped = prefs.getString("$owner.wrapped", null)
    if (salt == null || wrapped == null) return promise.reject("WIPED", "No offline data for this worker.")
    val dbKey =
        try {
          open(pinKey(pin, unb64(salt)), open(deviceKey(), unb64(wrapped)))
        } catch (e: AEADBadTagException) {
          val failed = prefs.getInt("$owner.failed", 0) + 1
          if (failed >= MAX_ATTEMPTS) {
            remove(owner)
            return promise.reject("WIPED", "Too many wrong PINs: this worker's offline data was deleted.")
          }
          prefs.edit().putInt("$owner.failed", failed).commit()
          return promise.reject("WRONG_PIN", (MAX_ATTEMPTS - failed).toString())
        } catch (e: Exception) {
          return promise.reject("STORE_FAILED", "The offline data could not be opened on this device.", e)
        }
    prefs.edit().putInt("$owner.failed", 0).commit()
    promise.resolve(hex(dbKey))
  }

  override fun removeStore(owner: String, promise: Promise) {
    remove(owner)
    promise.resolve(null)
  }

  override fun syntheticRecording(seconds: Double, promise: Promise) {
    promise.resolve(recording(synthetic(seconds)))
  }

  @SuppressLint("MissingPermission") // the JS side requests RECORD_AUDIO first
  override fun startRecording(promise: Promise) {
    if (!BuildConfig.ASHA_MICROPHONE_ENABLED) return promise.reject("MIC_OFF", "The microphone is off in this build.")
    if (recorder != null) return promise.reject("BUSY", "A recording is already running.")
    try {
      recorder = Recorder().also { it.start() }
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("MIC_FAILED", e.message, e)
    }
  }

  override fun stopRecording(promise: Promise) {
    val r = recorder ?: return promise.reject("NOT_RECORDING", "No recording is running.")
    recorder = null
    promise.resolve(recording(r.stop()))
  }

  override fun uploadRecording(url: String, bearer: String, base64: String, sha256: String, correlationId: String,
      promise: Promise) {
    // The bearer token goes only to this build's API, and only to a recording's content path (review I3).
    if (!url.startsWith(BuildConfig.ASHA_API_BASE + "/v1/voice-sessions/") || !url.endsWith("/content")) {
      return promise.reject("BAD_URL", "Uploads go only to this build's API.")
    }
    Thread {
      try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = "PUT"
          doOutput = true
          connectTimeout = 15000
          readTimeout = 60000
          setFixedLengthStreamingMode(bytes.size)
          setRequestProperty("Authorization", "Bearer $bearer")
          setRequestProperty("Content-Type", "audio/wav")
          setRequestProperty("X-Content-SHA256", sha256)
          setRequestProperty("X-Correlation-ID", correlationId)
        }
        conn.outputStream.use { it.write(bytes) }
        val status = conn.responseCode
        val body = (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        promise.resolve(Arguments.createMap().apply { putInt("status", status); putString("body", body) })
      } catch (e: java.io.IOException) {
        promise.reject("NETWORK", e.message, e)
      } catch (e: Exception) {
        promise.reject("UPLOAD_FAILED", e.message, e)
      }
    }.start()
  }

  // --- key handling ----------------------------------------------------------------------------

  private fun owners(): Set<String> = prefs.getStringSet("owners", emptySet())!!.toSet()

  private fun remove(owner: String) {
    prefs.edit()
        .remove("$owner.salt").remove("$owner.wrapped").remove("$owner.failed").remove("$owner.created")
        .putStringSet("owners", owners() - owner)
        .commit()
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(storeDir(), "asha-$owner.sqlite$suffix").delete()
  }

  private fun storeDir(): File = File(context.noBackupFilesDir, "asha-offline").apply { mkdirs() }

  private fun pinKey(pin: String, salt: ByteArray): SecretKey {
    val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
    val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    spec.clearPassword()
    return SecretKeySpec(raw, "AES")
  }

  /** The Keystore key that wraps every store on this device; it never leaves the Keystore. */
  private fun deviceKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build())
    return gen.generateKey()
  }

  /** AES-GCM; the output is IV followed by ciphertext and tag. */
  private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key) // the provider picks a fresh random IV
    return cipher.iv + cipher.doFinal(plain)
  }

  private fun open(key: SecretKey, sealed: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
    return cipher.doFinal(sealed, 12, sealed.size - 12)
  }

  // --- audio -----------------------------------------------------------------------------------

  private inner class Recorder {
    private val buffer = ByteArrayOutputStream()
    private val minSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    @SuppressLint("MissingPermission")
    private val record = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, max(minSize, RATE))
    @Volatile private var running = true
    private val thread = Thread {
      val chunk = ByteArray(minSize)
      while (running) {
        val n = record.read(chunk, 0, chunk.size)
        if (n > 0 && buffer.size() < RATE * 2 * MAX_SECONDS) buffer.write(chunk, 0, n)
      }
    }

    fun start() {
      record.startRecording()
      thread.start()
    }

    fun stop(): ShortArray {
      running = false
      thread.join(2000)
      record.stop()
      record.release()
      val bytes = buffer.toByteArray()
      val samples = ShortArray(bytes.size / 2)
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
      return samples
    }
  }

  /** A synthetic voiced signal (the same generator as the web app), different for every recording. */
  private fun synthetic(seconds: Double): ShortArray {
    val n = (RATE * seconds).toInt()
    val pcm = ShortArray(n)
    val base = 120 + random.nextDouble() * 60
    var phase = 0.0
    for (i in 0 until n) {
      val t = i.toDouble() / RATE
      val within = t % 0.34
      val envelope = if (within < 0.22) sin(PI * within / 0.22).pow(2) else 0.0
      phase += 2 * PI * (base + 25 * sin(2 * PI * 0.35 * t) + (random.nextDouble() * 3 - 1.5)) / RATE
      var voiced = 0.0
      for (k in 1..5) voiced += sin(k * phase) / k
      val gauss = sqrt(-2 * ln(max(random.nextDouble(), 1e-12))) * cos(2 * PI * random.nextDouble())
      val x = 0.5 * envelope * voiced / 1.6 + gauss * 0.004
      pcm[i] = (max(-1.0, min(1.0, x)) * 32767).roundToInt().toShort()
    }
    return pcm
  }

  private fun recording(pcm: ShortArray): WritableMap {
    val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
      put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
      putShort(2); putShort(16); put("data".toByteArray()); putInt(data.size)
    }.array()
    val wav = header + data
    return Arguments.createMap().apply {
      putString("base64", Base64.encodeToString(wav, Base64.NO_WRAP))
      putDouble("seconds", (pcm.size.toDouble() / RATE * 100).roundToInt() / 100.0)
      putString("sha256", hex(MessageDigest.getInstance("SHA-256").digest(wav)))
      putInt("size", wav.size)
    }
  }

  companion object {
    const val NAME = "AshaSecure"
    private const val KEY_ALIAS = "asha-offline-store-wrap"
    private const val ITERATIONS = 600_000
    private const val MAX_ATTEMPTS = 5
    private const val RATE = 16000
    private const val MAX_SECONDS = 180
    private val PIN = Regex("^[0-9]{6,12}$")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
  }
}
