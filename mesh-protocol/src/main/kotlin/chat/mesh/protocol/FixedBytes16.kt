package chat.mesh.protocol

/**
 * An immutable 128-bit value used for message IDs and temporary routing tokens.
 *
 * The constructor and accessor copy their input/output so mutable byte arrays
 * cannot silently change identity or routing values after validation.
 */
public class FixedBytes16 private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    public fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is FixedBytes16 && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = value.joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    public companion object {
        public const val SIZE_BYTES: Int = 16

        public fun from(bytes: ByteArray): FixedBytes16 {
            require(bytes.size == SIZE_BYTES) {
                "Expected $SIZE_BYTES bytes, received ${bytes.size}"
            }
            return FixedBytes16(bytes)
        }
    }
}
