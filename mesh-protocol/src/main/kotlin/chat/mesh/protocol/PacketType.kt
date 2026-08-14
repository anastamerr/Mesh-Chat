package chat.mesh.protocol

public enum class PacketType(public val wireValue: Int) {
    PRIVATE_MESSAGE(1),
    DELIVERY_ACK(2)
    ;

    public companion object {
        public fun fromWireValue(value: Int): PacketType? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}
