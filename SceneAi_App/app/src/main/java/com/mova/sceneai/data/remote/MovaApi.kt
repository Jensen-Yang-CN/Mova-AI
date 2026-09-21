package com.mova.sceneai.data.remote

import com.mova.sceneai.data.model.CapabilitiesDto
import com.mova.sceneai.data.model.ChatContextRequest
import com.mova.sceneai.data.model.ChatDto
import com.mova.sceneai.data.model.ChatReplyRequest
import com.mova.sceneai.data.model.ChatRewriteRequest
import com.mova.sceneai.data.model.FoodDto
import com.mova.sceneai.data.model.HealthDto
import com.mova.sceneai.data.model.ReadingDto
import com.mova.sceneai.data.model.ReadingTextRequest
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

/**
 * 云端服务接口。
 *
 * 每个方法都接收 `@Url`（完整地址）而不是依赖固定的 baseUrl —— 因为服务地址是
 * 用户在设置里随时可改的，如果写死在 Retrofit 里，每次改地址都要重建整个客户端。
 * 用 @Url 后全局只需要一个 Retrofit 实例。
 */
interface MovaApi {

    @GET
    suspend fun health(@Url url: String): HealthDto

    @GET
    suspend fun capabilities(@Url url: String): CapabilitiesDto

    // ---------- 做饭 ----------
    @Multipart
    @POST
    suspend fun analyzeImage(@Url url: String, @Part file: MultipartBody.Part): FoodDto

    // ---------- 阅读 ----------
    @POST
    suspend fun readingText(@Url url: String, @Body body: ReadingTextRequest): ReadingDto

    @Multipart
    @POST
    suspend fun readingImage(@Url url: String, @Part file: MultipartBody.Part): ReadingDto

    @Multipart
    @POST
    suspend fun readingPdf(@Url url: String, @Part file: MultipartBody.Part): ReadingDto

    // ---------- 聊天 ----------
    @POST
    suspend fun chatReply(@Url url: String, @Body body: ChatReplyRequest): ChatDto

    @POST
    suspend fun chatRewrite(@Url url: String, @Body body: ChatRewriteRequest): ChatDto

    @POST
    suspend fun chatContext(@Url url: String, @Body body: ChatContextRequest): ChatDto
}
