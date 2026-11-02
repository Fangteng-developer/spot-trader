package com.morris.spottrader.sec

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.morris.spottrader.ex.Creds
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class StoredKey(val exchange: String, val apiKey: String, val secret: String, val passphrase: String,
                     val keyType: String /* HMAC | RSA */, val rsaPriv: String /* b64, maybe password-encrypted */, val rsaProtected: Boolean, val rsaPub: String)

/** All secrets live in EncryptedSharedPreferences whose master key is held in Android Keystore (AES256-GCM). Nothing is logged. */
class KeyStoreRepo(private val prefs: SharedPreferences?) {
    companion object { fun create(ctx: Context) = KeyStoreRepo(EncryptedSharedPreferences.create(ctx, "secure_keys",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)) }
    /** decrypted RSA private keys kept in memory only for this session */
    private val unlocked = HashMap<String, ByteArray>()

    fun get(ex: String): StoredKey? = prefs?.getString("k_$ex", null)?.let { val o = JSONObject(it)
        StoredKey(ex, o.optString("apiKey"), o.optString("secret"), o.optString("pass"), o.optString("type", "HMAC"), o.optString("rsaPriv"), o.optBoolean("rsaProt"), o.optString("rsaPub")) }
    fun save(k: StoredKey) { prefs?.edit()?.putString("k_${k.exchange}", JSONObject().put("apiKey", k.apiKey).put("secret", k.secret).put("pass", k.passphrase)
        .put("type", k.keyType).put("rsaPriv", k.rsaPriv).put("rsaProt", k.rsaProtected).put("rsaPub", k.rsaPub).toString())?.apply(); unlocked.remove(k.exchange) }
    fun delete(ex: String) { prefs?.edit()?.remove("k_$ex")?.apply(); unlocked.remove(ex) }
    fun isLocked(ex: String) = get(ex)?.let { it.keyType == "RSA" && it.rsaProtected && unlocked[ex] == null } ?: false
    fun unlock(ex: String, password: String): Boolean { val k = get(ex) ?: return false
        return try { unlocked[ex] = Crypto.decryptWithPassword(k.rsaPriv, password); true } catch (e: Exception) { false } }

    /** Private key DER if available (unprotected, or unlocked this session). Never logged. */
    fun privateKeyDer(ex: String): ByteArray? { val k = get(ex) ?: return null; if (k.keyType != "RSA") return null
        return if (k.rsaProtected) unlocked[ex] else Base64.getDecoder().decode(k.rsaPriv) }
    fun creds(ex: String): Creds? { val k = get(ex) ?: return null
        if (k.keyType == "RSA") {
            val pk = if (k.rsaProtected) unlocked[ex] ?: return null else Base64.getDecoder().decode(k.rsaPriv)
            return Creds(k.apiKey, "", k.passphrase, pk) }
        return Creds(k.apiKey, k.secret, k.passphrase) }
}

object Crypto {
    private val rnd = SecureRandom()
    data class RsaPair(val privPkcs8: ByteArray, val publicPem: String, val privatePem: String)

    fun generateRsa2048(): RsaPair {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rnd) }.generateKeyPair()
        return RsaPair(kp.private.encoded, pem("PUBLIC KEY", kp.public.encoded), pem("PRIVATE KEY", kp.private.encoded))
    }
    fun pem(type: String, der: ByteArray) = "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
    /** Accepts a PKCS#8 PEM ("BEGIN PRIVATE KEY") pasted by the user. */
    fun parsePkcs8Pem(pem: String): ByteArray {
        require(!pem.contains("RSA PRIVATE KEY")) { "请提供PKCS#8格式（BEGIN PRIVATE KEY）私钥" }
        val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
        val der = Base64.getDecoder().decode(body)
        java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der)); return der
    }
    fun publicFromPrivate(der: ByteArray): String {
        val kf = java.security.KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der)) as java.security.interfaces.RSAPrivateCrtKey
        val pub = kf.generatePublic(java.security.spec.RSAPublicKeySpec(priv.modulus, priv.publicExponent))
        return pem("PUBLIC KEY", pub.encoded)
    }

    fun encryptedPem(der: ByteArray, pw: String) = "-----BEGIN SPOTTRADER ENCRYPTED PRIVATE KEY-----\nProc-Type: PBKDF2-SHA256-210000,AES-256-GCM,salt16|iv12|ct\n\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(Base64.getDecoder().decode(encryptWithPassword(der, pw))) + "\n-----END SPOTTRADER ENCRYPTED PRIVATE KEY-----\n"
    fun decryptPem(pem: String, pw: String): ByteArray {
        require(pw.isNotEmpty()) { "请先在下方输入私钥密码" }
        val body = pem.lines().filter { !it.startsWith("-----") && !it.contains(":") }.joinToString("").trim()
        val der = decryptWithPassword(body, pw); java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der)); return der }
    fun strongPassword(len: Int = 20): String {
        val sets = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#\$%^&*-_=+?")
        val all = sets.joinToString("")
        val chars = sets.map { it[rnd.nextInt(it.length)] }.toMutableList()
        while (chars.size < len) chars.add(all[rnd.nextInt(all.length)])
        chars.shuffle(rnd); return chars.joinToString("")
    }

    /** PBKDF2-HMAC-SHA256 (210k iters) -> AES-256-GCM. Output b64(salt|iv|ct). */
    fun encryptWithPassword(data: ByteArray, password: String): String {
        val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(salt + iv + c.doFinal(data))
    }
    fun decryptWithPassword(b64: String, password: String): ByteArray {
        val all = Base64.getDecoder().decode(b64)
        val salt = all.copyOfRange(0, 16); val iv = all.copyOfRange(16, 28)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }.doFinal(all.copyOfRange(28, all.size))
    }
    private fun derive(pw: String, salt: ByteArray) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 210_000, 256)).encoded, "AES")
}
