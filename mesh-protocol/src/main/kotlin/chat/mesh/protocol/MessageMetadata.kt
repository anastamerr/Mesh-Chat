package chat.mesh.protocol

/** Immutable metadata cryptographically bound to a private message or receipt. */
public data class MessageMetadata(
    public val messageId: FixedBytes16,
    public val recipientToken: FixedBytes16,
    public val createdAtEpochMillis: Long,
    public val expiresAtEpochMillis: Long,
) {
    init {
        require(createdAtEpochMillis >= 0) { "Creation time cannot be negative" }
        require(expiresAtEpochMillis > createdAtEpochMillis) {
            "Expiration must be later than creation"
        }
    }
}
