package me.devoxin.flight.api.cooldown

import me.devoxin.flight.api.CommandFunction

interface CooldownProvider {

    fun isOnCooldown(id: Long, bucket: BucketType, command: CommandFunction): Boolean

    fun getCooldownTime(id: Long, bucket: BucketType, command: CommandFunction): Long

    fun setCooldown(id: Long, bucket: BucketType, time: Long, command: CommandFunction)

    fun removeCooldown(id: Long, bucket: BucketType, command: CommandFunction)

    fun clearCooldowns(command: CommandFunction)

    fun clearCooldowns(id: Long, bucket: BucketType)

    fun clearCooldowns()

    fun shutdown()

    fun tryAcquire(id: Long, bucketType: BucketType, time: Long, command: CommandFunction): Boolean
}
