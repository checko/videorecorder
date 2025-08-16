package com.videorecorder

import android.util.Log
import java.nio.ByteBuffer

/**
 * Extracts individual MPEG-TS packets from MediaMuxer output for zero-gap recording.
 * MPEG-TS packets are 188 bytes each with a sync byte (0x47) at the beginning.
 */
class TsPacketExtractor {
    
    companion object {
        private const val TAG = "TsPacketExtractor"
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE = 0x47.toByte()
        const val TS_HEADER_SIZE = 4
    }
    
    private var totalPacketsExtracted = 0L
    private var invalidPacketsCount = 0L
    
    /**
     * Extracts TS packets from MediaMuxer output buffer
     * @param muxerOutput ByteBuffer containing MPEG-TS data from MediaMuxer
     * @return List of TsPacket objects ready for writing to file
     */
    fun extractPackets(muxerOutput: ByteBuffer): List<TsPacket> {
        val packets = mutableListOf<TsPacket>()
        val timestamp = System.nanoTime()
        
        // Process all complete 188-byte packets in the buffer
        while (muxerOutput.remaining() >= TS_PACKET_SIZE) {
            val packetData = ByteArray(TS_PACKET_SIZE)
            muxerOutput.get(packetData)
            
            // Validate sync byte to ensure packet integrity
            if (packetData[0] == TS_SYNC_BYTE) {
                val packet = TsPacket(
                    data = packetData,
                    timestamp = timestamp,
                    sequenceNumber = totalPacketsExtracted
                )
                packets.add(packet)
                totalPacketsExtracted++
                
                if (totalPacketsExtracted % 1000 == 0L) {
                    Log.d(TAG, "Extracted $totalPacketsExtracted TS packets")
                }
            } else {
                invalidPacketsCount++
                Log.w(TAG, "Invalid TS packet sync byte: 0x${String.format("%02X", packetData[0])}")
                
                // Try to find next sync byte to recover synchronization
                recoverSynchronization(muxerOutput, packetData)
            }
        }
        
        // Log any remaining bytes that don't form a complete packet
        if (muxerOutput.remaining() > 0) {
            Log.d(TAG, "Buffer has ${muxerOutput.remaining()} remaining bytes (incomplete packet)")
        }
        
        return packets
    }
    
    /**
     * Attempts to recover synchronization by finding the next sync byte
     */
    private fun recoverSynchronization(buffer: ByteBuffer, corruptedPacket: ByteArray) {
        // Look for sync byte in the corrupted packet data
        for (i in 1 until corruptedPacket.size) {
            if (corruptedPacket[i] == TS_SYNC_BYTE) {
                // Found potential sync byte, rewind buffer to align
                val rewindAmount = TS_PACKET_SIZE - i
                if (buffer.position() >= rewindAmount) {
                    buffer.position(buffer.position() - rewindAmount)
                    Log.d(TAG, "Recovered sync at offset $i, rewound $rewindAmount bytes")
                    return
                }
            }
        }
        
        Log.w(TAG, "Could not recover TS packet synchronization")
    }
    
    /**
     * Analyzes TS packet header for debugging and validation
     */
    fun analyzePacketHeader(packet: TsPacket): TsPacketInfo {
        val header = packet.data.sliceArray(0 until TS_HEADER_SIZE)
        
        // Parse TS header fields
        val syncByte = header[0]
        val transportErrorIndicator = (header[1].toInt() and 0x80) != 0
        val payloadUnitStart = (header[1].toInt() and 0x40) != 0
        val transportPriority = (header[1].toInt() and 0x20) != 0
        val pid = ((header[1].toInt() and 0x1F) shl 8) or (header[2].toInt() and 0xFF)
        val scramblingControl = (header[3].toInt() and 0xC0) shr 6
        val adaptationFieldControl = (header[3].toInt() and 0x30) shr 4
        val continuityCounter = header[3].toInt() and 0x0F
        
        return TsPacketInfo(
            syncByte = syncByte,
            transportErrorIndicator = transportErrorIndicator,
            payloadUnitStart = payloadUnitStart,
            transportPriority = transportPriority,
            pid = pid,
            scramblingControl = scramblingControl,
            adaptationFieldControl = adaptationFieldControl,
            continuityCounter = continuityCounter
        )
    }
    
    /**
     * Gets statistics about packet extraction
     */
    fun getStatistics(): ExtractionStatistics {
        return ExtractionStatistics(
            totalPacketsExtracted = totalPacketsExtracted,
            invalidPacketsCount = invalidPacketsCount,
            successRate = if (totalPacketsExtracted > 0) {
                (totalPacketsExtracted * 100.0) / (totalPacketsExtracted + invalidPacketsCount)
            } else 0.0
        )
    }
    
    /**
     * Resets extraction statistics
     */
    fun resetStatistics() {
        totalPacketsExtracted = 0L
        invalidPacketsCount = 0L
    }
}

/**
 * Represents a single MPEG-TS packet with metadata
 */
data class TsPacket(
    val data: ByteArray,
    val timestamp: Long,
    val sequenceNumber: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as TsPacket
        
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        if (sequenceNumber != other.sequenceNumber) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}

/**
 * Detailed information about a TS packet header
 */
data class TsPacketInfo(
    val syncByte: Byte,
    val transportErrorIndicator: Boolean,
    val payloadUnitStart: Boolean,
    val transportPriority: Boolean,
    val pid: Int,
    val scramblingControl: Int,
    val adaptationFieldControl: Int,
    val continuityCounter: Int
)

/**
 * Statistics about packet extraction process
 */
data class ExtractionStatistics(
    val totalPacketsExtracted: Long,
    val invalidPacketsCount: Long,
    val successRate: Double
)