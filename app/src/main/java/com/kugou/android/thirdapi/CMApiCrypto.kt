package com.kugou.android.thirdapi

import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 网易云第三方音乐控制协议的加解密。
 *
 * 协议逆向自 OPPO 小布(Breeno) com.heytap.speechassist 的
 * com.netease.cloudmusic.third.api.contract 客户端实现（NetEasePlayer / a / e / g 类）：
 *  - encRequest = Base64(RSA_Encrypt_PublicKey(JSON{appId, ts, sign}))
 *  - sign       = Base64(SHA256withRSA_Sign(ts, PrivateKey))
 *  - encResult  = Base64(RSA_Encrypt_PublicKey(JSON{sign, ts, token, expireTime}))
 *  小布侧用 RSA_Decrypt(PrivateKey) 解 encResult，并用 PublicKey 对 ts 验签。
 */
object CMApiCrypto {

    const val APP_ID = "a301010000000000b2a26349c837ea4c"

    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2InjSmQR7YVO8/TBl0yZPWX6fAfcx9i+" +
            "lxvmd2MW3xJgbYygkzU4Sc27VUXSHITj4UBiQ4KGY+tTgH9KJUNCHbbEwTS6z+rheayJ/UUO5O4f" +
            "m6kh47sXSwz/A15pF0isLgnOvx+ADU/mJPKkYXVfGnFFXeMLXp1oZeNKi/jJw9Y7LLxgOEsMzcMW" +
            "8sZ0okC4CT2FlT+2OJq4yd0j9Tp/O8g6bxRMXiD7jrckumbsHXmtsLT1QMAGlHtmmJO2dKRZckAn" +
            "jfDjwN+7ir+W5VuBF8U2M+klIJaxbat8XZv/nb/QzabH4cmWmU21Tt0VZuZlRgD87r1xFxaGFpO6" +
            "oiGCiQIDAQAB"

    private const val PRIVATE_KEY_B64 =
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC8AMuEjlRR1xCn+BO5B2IudRyx" +
            "1Sn5VHkCC4HKNfuda28G5oIGhFNjTjNSfspc2eaSd19vE4KkBSyfd2uTy7eAEG0tXjklABsrK+Pw" +
            "b4Cujf2HsZla4QG/mlFmieU3vtv9oACPbMdNjKdrKCM7ZPwoWHpFeg1PRAewgGDu25t449bFPMdV" +
            "1wxrJM67r2jkG0o+SpRockx24b167eLQHXcdA9BVn1ZCQXRjNBXK1dAIDahHiUQpQdp1AeQMHLPH" +
            "LveWpc126Fs9gcxzorenjDK8THIG4sGfo52FbgOsU6Kdt+AoMRuBlPApDMT66b87DNm+mj3HxE2g" +
            "qiaPTFf6MWfJAgMBAAECggEASGqV+i1I1W1eARVRo4fwjayWrPlK/btIET2VdOpuTSjAz4uishfu" +
            "duX8Zt5n3HQimHBa1lJRlBRaJ+K8EWX6/N1W8I7GvDXaQTraxhZtHs+axNXoGFVrcv5iNHkRTfO6" +
            "kWs8TAK+kmSHaINBbvNrHa9XCcQFtG6j95y7ucXlkR6ogVJe7D9txS4bTjl9k8YGr11lXE0SdnZX" +
            "qmOSwfSvUW89wwexXwjjmMiYBhZzro8vwUI/p5vjT/6GZ4jcsm2OEZRa8/R3nueqVq1jo2CcyDLm" +
            "UX153ZuNJ2A4Qc3JRKAdLciwRoI0KJBDkWqabqHAmajzBHpC5T7G2ygMxb84MQKBgQDrcP8+L/Hr" +
            "De13h79g0OWmFhjQUEOXZrV9Q49aRvirHjTivPWrHxO7JU7O+SHCyfsTf1SzZtnHol2ESSIECe+O" +
            "Bl2Xg1idJlaTbfGHfTO+COoS3UWXfwspLNon0f0mSQNoVtX50IpX7R+33mScHgtJYukSc8jfMQdC" +
            "eVvhcHgTZwKBgQDMa1+RCA58OLWxX5347osqfb5FzR08l7B33ODlDAJYU1vGWQsj1Bik5vb+taX/" +
            "pKuv6jfh6MjpNIuQqTp9z+Jk042mrQbDC7JJ34YqrnXSmS7e0FWdtCdAraBllojVynLRKUeuNrcq" +
            "94fXq+RiGMkbQW5vN8byo7QhMjk8WdFdTwKBgQDOmrwBMEWURQaLi73vEaFUm7NuqeCGbYT5xV14" +
            "lSsFzl4OUk8quPyxj02W5pwvuNrxAx2qyKh3PQ3fVeXZsXZcJU3f9Uy/qrLycNowUTgknOH7Z9z3" +
            "0m5S1Z8irmz9ObkZyInAzLV57wWUbj3PmbXB0mBA5wXwha+fL6vnKrDUJQKBgFrSwg0wJS9wCtoZ" +
            "/5ggdFiWsblVns3TH6bBznI12bzgnIAOA/MRQznRHKCimHRy940bZWTMBqgGnpPRfJl1icL+c4tA" +
            "iSaTxc0osPW5ucOuJ7L7oW6GIoKMIh3Aul8yqbzguGZhDRTcEdDzIG+tT3z6n1Ru1cfskBXHuMJl" +
            "ra3lAoGABU7o88+CcrOPLxtwIuKBkmOn3fV4+aRLN+DXq45nySx198VoZCeSsCbjvVvHIVxAy5Ic" +
            "cHX8Bu7s8C+8daFgNOcU8LVA5qMVRRSntXfGBAqOp780MfL3KC2E2af4kenV17EEji1zx2jZcznw" +
            "7Zyy8m/4Wnrs0dKROQZtOlqbums="

    private val publicKey: PublicKey by lazy {
        val bytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }

    private val privateKey: PrivateKey by lazy {
        val bytes = Base64.decode(PRIVATE_KEY_B64, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    /** RSA/ECB/PKCS1Padding 公钥加密 → Base64（分段，单块上限 245 字节） */
    fun encryptPublic(plain: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val data = plain.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val len = minOf(245, data.size - off)
            out.write(cipher.doFinal(data, off, len))
            off += len
        }
        return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)
    }

    /** RSA/ECB/PKCS1Padding 私钥解密 */
    fun decryptPrivate(b64: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val data = Base64.decode(b64, Base64.DEFAULT)
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val len = minOf(256, data.size - off)
            out.write(cipher.doFinal(data, off, len))
            off += len
        }
        return String(out.toByteArray())
    }

    /** SHA256withRSA 私钥签名 → Base64 */
    fun sign(data: String): String {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(privateKey)
        s.update(data.toByteArray())
        return Base64.encodeToString(s.sign(), Base64.DEFAULT)
    }

    /** SHA256withRSA 公钥验签 */
    fun verify(signB64: String, data: String): Boolean = try {
        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(publicKey)
        s.update(data.toByteArray())
        s.verify(Base64.decode(signB64, Base64.DEFAULT))
    } catch (_: Throwable) {
        false
    }
}
