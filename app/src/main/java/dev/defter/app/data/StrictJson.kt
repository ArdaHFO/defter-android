package dev.defter.app.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader

/** Validate with an iterative strict lexer before handing bounded input to recursive org.json. */
internal object StrictJson {
    fun objectFromText(text: String): JSONObject {
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Belge bir JSON nesnesi olmalı." }
                var depth = 0
                val names = ArrayDeque<MutableSet<String>?>()
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            require(++depth <= 64) { "Belge yapısı çok derin." }
                            reader.beginObject(); names.addLast(mutableSetOf())
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            require(++depth <= 64) { "Belge yapısı çok derin." }
                            reader.beginArray(); names.addLast(null)
                        }
                        JsonToken.END_OBJECT -> { reader.endObject(); names.removeLast(); depth-- }
                        JsonToken.END_ARRAY -> { reader.endArray(); names.removeLast(); depth-- }
                        JsonToken.NAME -> require(names.last()!!.add(reader.nextName())) { "Tekrarlanan JSON alanı." }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> throw IOException("Belge yapısı geçersiz.")
                    }
                }
                require(depth == 0) { "Belge tamamlanmamış." }
            }
            return JSONObject(text)
        } catch (error: Exception) {
            throw IOException("Belge dosyası bozuk. Mevcut dosya korunuyor.", error)
        }
    }
}
