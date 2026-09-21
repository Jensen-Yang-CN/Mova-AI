package com.mova.sceneai.data.remote

import com.mova.sceneai.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiFactory {

    val json: Json = Json {
        // 契约演进友好：服务端多字段不炸、少字段走默认值、null 显式忽略
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * 超时给得比较宽松：真正的超时控制由 Repository 按用户设置用 withTimeout 施加，
     * 这样"超时时间"这个设置项才有意义，而不是写在 OkHttp 里永远改不了。
     */
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                )
            }
        }
        .build()

    /** 用完整地址调用，因此 baseUrl 只是一个占位符。 */
    fun createApi(client: OkHttpClient, json: Json): MovaApi =
        Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MovaApi::class.java)

    /** 把字节数组包装成 multipart 的 file 部分。 */
    fun imagePart(bytes: ByteArray, filename: String, mime: String = "image/jpeg"): MultipartBody.Part {
        val body = bytes.toRequestBody(mime.toMediaType())
        return MultipartBody.Part.createFormData("file", filename, body)
    }

    fun pdfPart(bytes: ByteArray, filename: String = "document.pdf"): MultipartBody.Part {
        val body = bytes.toRequestBody("application/pdf".toMediaType())
        return MultipartBody.Part.createFormData("file", filename, body)
    }
}
