package com.meshwalk.app.data.local.converter

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? = value?.let {
        android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
    }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? = value?.let {
        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
    }
}
