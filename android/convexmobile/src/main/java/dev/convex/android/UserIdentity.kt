package dev.convex.android

import kotlinx.serialization.Serializable

/**
 * A user identity for an admin-authenticated client to act as; see [ConvexClient.setAdminAuth].
 *
 * Mirrors `UserIdentityAttributes` in the JavaScript and Rust clients, and encodes to the JSON
 * shape the Convex sync protocol uses, leaving out unset fields. Either [tokenIdentifier] or both
 * [issuer] and [subject] must be set; when [tokenIdentifier] is `null` the backend derives it as
 * `"<issuer>|<subject>"`.
 */
@Serializable
data class UserIdentity(
    val tokenIdentifier: String? = null,
    val issuer: String? = null,
    val subject: String? = null,
    val name: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val nickname: String? = null,
    val preferredUsername: String? = null,
    val profileUrl: String? = null,
    val pictureUrl: String? = null,
    val websiteUrl: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val gender: String? = null,
    val birthday: String? = null,
    val timezone: String? = null,
    val language: String? = null,
    val phoneNumber: String? = null,
    val phoneNumberVerified: Boolean? = null,
    val address: String? = null,
    /** An RFC 3339 timestamp. */
    val updatedAt: String? = null,
)
