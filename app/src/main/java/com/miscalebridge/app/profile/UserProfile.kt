package com.miscalebridge.app.profile

import kotlinx.serialization.Serializable

enum class Sex { MALE, FEMALE }

@Serializable
data class UserProfile(
    val macAddress: String,
    val bindkeyHex: String,
    val heightCm: Int,
    val age: Int,
    val sex: Sex,
    val profileId: Int,
)
