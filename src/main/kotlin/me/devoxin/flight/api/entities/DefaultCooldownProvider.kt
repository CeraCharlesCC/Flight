package me.devoxin.flight.api.entities

import me.devoxin.flight.api.CommandFunction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class DefaultCooldownProvider : CooldownProvider {
    private val buckets = ConcurrentHashMap<BucketType, Bucket>()

    override fun isOnCooldown(id: Long, bucket: BucketType, command: CommandFunction): Boolean {
        return buckets[bucket]?.isOnCooldown(id, command.name) ?: false
    }

    override fun getCooldownTime(id: Long, bucket: BucketType, command: CommandFunction): Long {
        return buckets[bucket]?.getCooldownRemainingTime(id, command.name) ?: 0L
    }

    override fun setCooldown(id: Long, bucket: BucketType, time: Long, command: CommandFunction) {
        buckets.computeIfAbsent(bucket) { Bucket() }
            .setCooldown(id, time, command.name)
    }

    override fun removeCooldown(id: Long, bucket: BucketType, command: CommandFunction) {
        buckets[bucket]?.removeCooldown(id, command.name)
    }

    override fun clearCooldowns(command: CommandFunction) {
        buckets.values.forEach { it.clearCooldown(command.name) }
    }

    override fun clearCooldowns(id: Long, bucket: BucketType) {
        buckets[bucket]?.clearCooldowns(id)
    }

    override fun clearCooldowns() {
        buckets.values.forEach { it.empty() }
    }


    override fun shutdown() {
        buckets.values.forEach { it.shutdown() }
        buckets.clear()
    }

    class Bucket {
        private val sweeperThread = Executors.newSingleThreadScheduledExecutor()

        private val cooldowns = ConcurrentHashMap<Long, MutableMap<String, Long>>()

        fun isOnCooldown(id: Long, commandName: String): Boolean {
            return getCooldownRemainingTime(id, commandName) > 0L
        }

        fun getCooldownRemainingTime(id: Long, commandName: String): Long {
            val expiresAt = cooldowns[id]?.get(commandName) ?: return 0L
            val remaining = expiresAt - System.currentTimeMillis()
            return if (remaining > 0L) remaining else 0L
        }

        fun setCooldown(id: Long, time: Long, commandName: String) {
            val entityCooldowns = cooldowns.computeIfAbsent(id) { ConcurrentHashMap() }
            val expiresAt = System.currentTimeMillis() + time

            entityCooldowns[commandName] = expiresAt

            sweeperThread.schedule({
                cooldowns[id]?.let { map ->
                    map.computeIfPresent(commandName) { _, stored ->
                        if (stored == expiresAt && stored <= System.currentTimeMillis()) {
                            null
                        } else {
                            stored
                        }
                    }

                    if (map.isEmpty()) {
                        cooldowns.remove(id, map)
                    }
                }
            }, time, TimeUnit.MILLISECONDS)
        }

        fun removeCooldown(id: Long, commandName: String) {
            cooldowns[id]?.let { map ->
                map.remove(commandName)
                if (map.isEmpty()) {
                    cooldowns.remove(id, map)
                }
            }
        }

        fun clearCooldown(commandName: String) {
            cooldowns.entries.forEach { (id, map) ->
                map.remove(commandName)
                if (map.isEmpty()) {
                    cooldowns.remove(id, map)
                }
            }
        }

        fun clearCooldowns(id: Long) {
            cooldowns.remove(id)
        }

        fun empty() {
            cooldowns.clear()
        }

        fun shutdown() {
            sweeperThread.shutdown()
            cooldowns.clear()
        }
    }
}
