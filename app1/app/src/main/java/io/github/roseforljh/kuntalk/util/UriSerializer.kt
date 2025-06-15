package io.github.roseforljh.kuntalk.util // 确保包名正确

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("android.net.Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        // �?Uri 对象转换为其字符串表示形式进行编�?
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        // 从解码的字符串解析回 Uri 对象
        return Uri.parse(decoder.decodeString())
    }
}
