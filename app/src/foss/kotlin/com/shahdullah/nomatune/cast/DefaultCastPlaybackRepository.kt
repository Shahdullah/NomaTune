package com.shahdullah.nomatune.cast

class DefaultCastPlaybackRepository : CastPlaybackRepository {
    override val state get() = CastScreenState.Unavailable
    override suspend fun connect(deviceId: String) = Unit
    override suspend fun disconnect() = Unit
    override fun dispose() = Unit
}
