package dev.alorebb.kopi.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

// Protocol message models. The wire format is normative in protocol/PROTOCOL.md
// and pinned by protocol/fixtures/*.json. The "type" key discriminates cases.
// Ids are lowercase UUID strings, matching the fixtures.
val KopiJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface KopiMessage

@Serializable
@SerialName("hello")
data class Hello(val protocolVersion: Int, val app: String) : KopiMessage

@Serializable
@SerialName("pair")
data class Pair(val code: String, val name: String) : KopiMessage

@Serializable
@SerialName("paired")
data class Paired(val name: String) : KopiMessage

@Serializable
enum class ClipKind {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,
}

@Serializable
@SerialName("clip")
data class Clip(
    val id: String,
    val ts: Long,
    val kind: ClipKind,
    val text: String,
    val sensitive: Boolean,
) : KopiMessage

@Serializable
@SerialName("clip_offer")
data class ClipOffer(
    val id: String,
    val ts: Long,
    val kind: ClipKind,
    val mime: String,
    val size: Int,
) : KopiMessage

@Serializable
@SerialName("clip_request")
data class ClipRequest(val id: String) : KopiMessage

@Serializable
@SerialName("clip_data")
data class ClipData(val id: String, val size: Int) : KopiMessage

@Serializable
enum class UnavailableReason {
    @SerialName("expired")
    EXPIRED,

    @SerialName("replaced")
    REPLACED,
}

@Serializable
@SerialName("clip_unavailable")
data class ClipUnavailable(val id: String, val reason: UnavailableReason) : KopiMessage

@Serializable
enum class ErrorCode {
    @SerialName("version_mismatch")
    VERSION_MISMATCH,

    @SerialName("not_paired")
    NOT_PAIRED,

    @SerialName("invalid_code")
    INVALID_CODE,
}

@Serializable
@SerialName("error")
data class KopiError(val code: ErrorCode) : KopiMessage
