package com.KinsDirectory.eldenFactions

// ★ Crafted by Kin ★
// Plugin: eldenFactions – Full Territory Protection, Claim Limits, Invite System (Simple)

import org.bukkit.*
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.event.Cancellable
import java.io.File
import java.util.*
import kotlin.math.min

class EldenFactions : JavaPlugin(), TabExecutor, Listener {

    private lateinit var factionDataFile: File
    private lateinit var factionData: YamlConfiguration

    override fun onEnable() {
        logger.info("EldenFactions loading...")

        setupFiles()
        getCommand("f")?.setExecutor(this)
        getCommand("f")?.tabCompleter = this
        server.pluginManager.registerEvents(this, this)
        startPowerRegenTask()

        logger.info("EldenFactions enabled successfully.")
    }

    override fun onDisable() {
        logger.info("EldenFactions disabled.")
    }

    private fun setupFiles() {
        val dataFolder = File(dataFolder, "data")
        if (!dataFolder.exists()) dataFolder.mkdirs()

        factionDataFile = File(dataFolder, "plugin.yml")
        if (!factionDataFile.exists()) {
            factionDataFile.createNewFile()
        }
        factionData = YamlConfiguration.loadConfiguration(factionDataFile)
    }

    private fun saveFactions() {
        factionData.save(factionDataFile)
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        val uuid = sender.uniqueId.toString()

        if (args.isEmpty()) {
            sender.sendMessage(color("&cUsage: /f <create|info|claim|invite|join|kick|leave|disband>"))
            return true
        }

        when (args[0].lowercase()) {
            "create" -> {
                if (args.size < 2) {
                    sender.sendMessage(color("&cUsage: /f create <name>"))
                    return true
                }
                val name = args[1]
                if (factionData.contains("factions.$name")) {
                    sender.sendMessage(color("&cA faction with that name already exists."))
                    return true
                }
                factionData.set("factions.$name.leader", uuid)
                factionData.set("factions.$name.members.$uuid", "Leader")
                factionData.set("players.$uuid.faction", name)
                factionData.set("players.$uuid.role", "Leader")
                factionData.set("players.$uuid.power", 10.0)
                saveFactions()
                sender.sendMessage(color("&aFaction '$name' created successfully."))
            }

            "info" -> {
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You are not in a faction.")
                val leaderUUID = factionData.getString("factions.$faction.leader")
                val power = factionData.getDouble("players.$uuid.power", 0.0)
                val members = factionData.getConfigurationSection("factions.$faction.members")?.getKeys(false)?.size ?: 1
                sender.sendMessage(color("&6Faction: &f$faction"))
                sender.sendMessage(color("&6Leader: &f${Bukkit.getOfflinePlayer(UUID.fromString(leaderUUID)).name}"))
                sender.sendMessage(color("&6Members: &f$members"))
                sender.sendMessage(color("&6Your Power: &f${"%.1f".format(power)}"))
            }

            "claim" -> {
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You must be in a faction to claim land.")
                val chunk = sender.location.chunk
                val key = chunkKey(chunk)
                val claimedBy = factionData.getString("claims.$key")
                if (claimedBy != null) return sender.error("This land is already claimed by $claimedBy.")

                val totalPower = getFactionTotalPower(faction)
                val claimCount = factionData.getConfigurationSection("claims")?.getKeys(false)?.count { factionData.getString("claims.$it") == faction } ?: 0
                if (claimCount >= totalPower.toInt()) return sender.error("Your faction has reached the maximum claim limit ($totalPower).")

                factionData.set("claims.$key", faction)
                saveFactions()
                sender.sendMessage(color("&aChunk claimed for faction: &f$faction"))
            }

            "invite" -> {
                if (args.size < 2) return sender.error("Usage: /f invite <player>")
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You are not in a faction.")
                val target = Bukkit.getPlayer(args[1]) ?: return sender.error("Player not found.")
                val targetUUID = target.uniqueId.toString()
                factionData.set("factions.$faction.invites.$targetUUID", true)
                saveFactions()
                sender.sendMessage(color("&eInvite sent to ${target.name}"))
                target.sendMessage(color("&6You were invited to join faction: &f$faction &7(/f join $faction)"))
            }

            "join" -> {
                if (args.size < 2) return sender.error("Usage: /f join <faction>")
                val targetFaction = args[1]
                if (!factionData.contains("factions.$targetFaction")) return sender.error("Faction does not exist.")
                if (factionData.getBoolean("factions.$targetFaction.invites.$uuid") != true) return sender.error("You were not invited.")

                factionData.set("players.$uuid.faction", targetFaction)
                factionData.set("players.$uuid.role", "Member")
                factionData.set("factions.$targetFaction.members.$uuid", "Member")
                factionData.set("factions.$targetFaction.invites.$uuid", null)
                if (!factionData.contains("players.$uuid.power")) factionData.set("players.$uuid.power", 10.0)
                saveFactions()
                sender.sendMessage(color("&aJoined faction: $targetFaction"))
            }

            "kick" -> {
                if (args.size < 2) return sender.error("Usage: /f kick <player>")
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You are not in a faction.")
                val role = factionData.getString("players.$uuid.role")
                if (role != "Leader") return sender.error("Only leaders can kick.")
                val target = Bukkit.getOfflinePlayer(args[1])
                val tid = target.uniqueId.toString()
                if (factionData.getString("players.$tid.faction") != faction) return sender.error("That player is not in your faction.")
                factionData.set("players.$tid.faction", null)
                factionData.set("players.$tid.role", null)
                factionData.set("factions.$faction.members.$tid", null)
                saveFactions()
                sender.sendMessage(color("&cKicked ${target.name} from faction."))
            }

            "leave" -> {
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You are not in a faction.")
                factionData.set("factions.$faction.members.$uuid", null)
                factionData.set("players.$uuid.faction", null)
                factionData.set("players.$uuid.role", null)
                saveFactions()
                sender.sendMessage(color("&cYou left the faction."))
            }

            "disband" -> {
                val faction = factionData.getString("players.$uuid.faction") ?: return sender.error("You are not in a faction.")
                val role = factionData.getString("players.$uuid.role")
                if (role != "Leader") return sender.error("Only the leader can disband.")
                factionData.set("factions.$faction", null)
                val players = factionData.getConfigurationSection("players")?.getKeys(false) ?: return true
                for (id in players) {
                    if (factionData.getString("players.$id.faction") == faction) {
                        factionData.set("players.$id.faction", null)
                        factionData.set("players.$id.role", null)
                    }
                }
                saveFactions()
                sender.sendMessage(color("&cFaction disbanded: $faction"))
            }
        }

        return true
    }

    private fun getFactionTotalPower(faction: String): Double {
        val players = factionData.getConfigurationSection("factions.$faction.members")?.getKeys(false) ?: return 0.0
        return players.sumOf { id -> factionData.getDouble("players.$id.power", 0.0) }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            return listOf("create", "info", "claim", "invite", "join", "kick", "leave", "disband").filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun chunkKey(chunk: Chunk): String = "${chunk.world.name}_${chunk.x}_${chunk.z}"

    private fun color(msg: String): String = ChatColor.translateAlternateColorCodes('&', msg)

    private fun CommandSender.error(msg: String): Boolean {
        this.sendMessage(color("&c[eldenFactions] $msg"))
        return true
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val uuid = player.uniqueId.toString()
        if (!factionData.contains("players.$uuid")) return
        val current = factionData.getDouble("players.$uuid.power", 10.0)
        val updated = (current - 2.0).coerceAtLeast(0.0)
        factionData.set("players.$uuid.power", updated)
        saveFactions()
        player.sendMessage(color("&cYou lost &42.0 power&c. Current power: &f${"%.1f".format(updated)}"))
    }

    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        checkProtection(e.player, e.block.chunk, e)
    }

    @EventHandler
    fun onBlockPlace(e: BlockPlaceEvent) {
        checkProtection(e.player, e.block.chunk, e)
    }

    private fun checkProtection(player: Player, chunk: Chunk, event: Cancellable) {
        val chunkKey = chunkKey(chunk)
        val ownerFaction = factionData.getString("claims.$chunkKey") ?: return
        val playerFaction = factionData.getString("players.${player.uniqueId}.faction") ?: return event.cancel("You can't build here.", player)
        if (ownerFaction != playerFaction) event.cancel("You can't build in $ownerFaction territory.", player)
    }

    private fun Cancellable.cancel(msg: String, player: Player) {
        this.isCancelled = true
        player.sendMessage(color("&c[eldenFactions] $msg"))
    }

    private fun startPowerRegenTask() {
        object : BukkitRunnable() {
            override fun run() {
                var changed = false
                val playersSection = factionData.getConfigurationSection("players") ?: return
                for (uuid in playersSection.getKeys(false)) {
                    val key = "players.$uuid.power"
                    val current = factionData.getDouble(key, 0.0)
                    if (current < 10.0) {
                        val updated = min(10.0, current + 0.5)
                        factionData.set(key, updated)
                        changed = true
                    }
                }
                if (changed) saveFactions()
            }
        }.runTaskTimer(this, 20 * 300, 20 * 300)
    }
}
