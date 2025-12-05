package me.devoxin.flight.api.entities

import me.devoxin.flight.api.CommandFunction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class DefaultCooldownProvider : CooldownProvider {
    private val buckets = ConcurrentHashMap<BucketType, Bucket>()

    override fun tryAcquire(
        id: Long,
        bucket: BucketType,
        time: Long,
        command: CommandFunction
    ): Boolean {
        val bucket = buckets.computeIfAbsent(bucket) { Bucket() }
        return bucket.tryAcquire(id, command.name, time)
    }

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

        fun tryAcquire(id: Long, commandName: String, time: Long): Boolean {
            val now = System.currentTimeMillis()
            val expiresAt = now + time

            var acquired = false

            cooldowns.compute(id) { _, existing ->
                val map = (existing ?: ConcurrentHashMap<String, Long>())

                val current = map[commandName]
                if (current == null || current <= now) {
                    map[commandName] = expiresAt
                    acquired = true
                }

                map.ifEmpty { null }
            }

            if (acquired) {
                sweeperThread.schedule({
                    val currentTime = System.currentTimeMillis()

                    cooldowns.compute(id) { _, map ->
                        if (map == null) {
                            return@compute null
                        }

                        val stored = map[commandName]

                        if (stored != null && stored == expiresAt && stored <= currentTime) {
                            map.remove(commandName)
                        }

                        map.ifEmpty { null }
                    }
                }, time, TimeUnit.MILLISECONDS)
            }

            return acquired
        }

        fun isOnCooldown(id: Long, commandName: String): Boolean {
            return getCooldownRemainingTime(id, commandName) > 0L
        }

        fun getCooldownRemainingTime(id: Long, commandName: String): Long {
            val expiresAt = cooldowns[id]?.get(commandName) ?: return 0L
            val remaining = expiresAt - System.currentTimeMillis()
            return remaining.coerceAtLeast(0L)
        }

        fun setCooldown(id: Long, time: Long, commandName: String) {
            val expiresAt = System.currentTimeMillis() + time

            cooldowns.compute(id) { _, existing ->
                val entityCooldowns = (existing ?: ConcurrentHashMap())
                entityCooldowns[commandName] = expiresAt
                entityCooldowns
            }

            sweeperThread.schedule({
                val now = System.currentTimeMillis()

                cooldowns.compute(id) { _, map ->
                    if (map == null) {
                        return@compute null
                    }

                    val stored = map[commandName]

                    if (stored != null && stored == expiresAt && stored <= now) {
                        map.remove(commandName)
                    }

                    map.ifEmpty { null }
                }
            }, time, TimeUnit.MILLISECONDS)
        }

        fun removeCooldown(id: Long, commandName: String) {
            cooldowns.computeIfPresent(id) { _, map ->
                map.remove(commandName)
                map.ifEmpty { null }
            }
        }

        fun clearCooldown(commandName: String) {
            cooldowns.keys.forEach { id ->
                cooldowns.computeIfPresent(id) { _, map ->
                    map.remove(commandName)
                    map.ifEmpty { null }
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
            sweeperThread.shutdownNow()
            cooldowns.clear()
        }
    }
}
