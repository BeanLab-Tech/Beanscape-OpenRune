package org.rsmod.content.beanlab.ge

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.content.beanlab.core.BeanlabSchema
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.type.uncert
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/** Process-wide Grand Exchange state, restored from the database on startup. */
public object GeState {
    public val book: GeBook = GeBook()

    /** `false` until persisted offers are loaded; trading before then could reuse offer ids. */
    @Volatile public var ready: Boolean = false

    @Volatile public var restoring: Boolean = false
}

public class GeCommands
@Inject
constructor(
    private val playerList: PlayerList,
    private val db: GameDbManager,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onCommand("ge") {
            desc = "Grand Exchange: buy, sell, offers, cancel, collect, price"
            cheat { ge(this) }
        }
        restoreOffers()
    }

    private fun ge(cheat: Cheat) =
        with(cheat) {
            if (!GeState.ready) {
                restoreOffers()
                player.mes("The Grand Exchange is still loading - try again shortly.")
                return@with
            }
            val parts = args.toList()
            val launched = protectedAccess.launch(player) { handleGe(parts) }
            if (!launched) {
                player.mes("You are busy - try again shortly.")
            }
        }

    private suspend fun ProtectedAccess.handleGe(parts: List<String>) {
        when (parts.firstOrNull()?.lowercase()) {
            "buy" -> buy(parts)
            "sell" -> sell(parts)
            "offers" -> listOffers()
            "cancel" -> cancel(parts)
            "collect" -> collect()
            "price" -> price(parts)
            else ->
                mes(
                    "Grand Exchange: ::ge buy <id> <qty> <price>, ::ge sell <id> <qty> <price>, " +
                        "::ge offers, ::ge cancel <slot>, ::ge collect, ::ge price <id>"
                )
        }
    }

    private data class OfferArgs(val type: ItemServerType, val qty: Int, val price: Long)

    /**
     * Parses `<item id> <qty> <price each>` and resolves the item to its tradeable, un-noted form
     * so noted and un-noted stock share one market. Returns `null` after telling the player why.
     */
    private fun ProtectedAccess.parseOfferArgs(verb: String, parts: List<String>): OfferArgs? {
        if (parts.size < 4) {
            mes("Usage: ::ge $verb <item id> <qty> <price each>")
            return null
        }
        val itemId = parts[1].toIntOrNull()
        val qty = parts[2].toIntOrNull()
        val price = parts[3].toLongOrNull()
        if (itemId == null || qty == null || price == null || qty <= 0 || price <= 0) {
            mes("Invalid item id, quantity or price.")
            return null
        }
        val requested = item(itemId)
        if (requested == null) {
            mes("Unknown item id: $itemId")
            return null
        }
        val type = uncert(requested)
        if (type.id == COINS || !type.tradeable) {
            mes("${itemName(type)} cannot be traded on the Grand Exchange.")
            return null
        }
        if (qty.toLong() * price > GeTypes.MAX_OFFER_VALUE) {
            mes("Offer value too large - the total must not exceed ${GeTypes.MAX_OFFER_VALUE} gp.")
            return null
        }
        return OfferArgs(type, qty, price)
    }

    private fun ProtectedAccess.claimSlot(accountId: Int): Int? {
        val slot = GeState.book.freeSlot(accountId)
        if (slot == null) {
            mes(
                "All ${GeTypes.MAX_SLOTS_PER_ACCOUNT} Grand Exchange slots are in use - " +
                    "::ge collect or ::ge cancel to free one."
            )
        }
        return slot
    }

    private suspend fun ProtectedAccess.buy(parts: List<String>) {
        val (type, qty, price) = parseOfferArgs("buy", parts) ?: return
        val accountId = player.accountId
        val slot = claimSlot(accountId) ?: return
        val totalCost = (qty.toLong() * price).toInt()
        if (invCoinTotal() < totalCost) {
            mes("You need $totalCost coins but only have ${invCoinTotal()}.")
            return
        }
        if (!invTakeFee(totalCost)) {
            mes("Not enough coins to escrow the buy order.")
            return
        }
        val placement =
            GeState.book.placeBuy(
                accountId = accountId,
                slot = slot,
                itemId = type.id,
                quantity = qty,
                pricePer = price,
                now = System.currentTimeMillis(),
            )
        persist(placement.touched, placement.trades)
        val name = itemName(type)
        if (placement.offer.state == GeOfferState.Completed) {
            mes("Buy order filled instantly: $qty x $name. ::ge collect to claim.")
        } else {
            mes("Buy offer placed in slot $slot: $qty x $name @ $price gp each.")
        }
    }

    private suspend fun ProtectedAccess.sell(parts: List<String>) {
        val (type, qty, price) = parseOfferArgs("sell", parts) ?: return
        val accountId = player.accountId
        val slot = claimSlot(accountId) ?: return
        val noted = certOf(type)
        val haveUnnoted = invTotal(inv, type.internalName)
        val haveNoted = if (noted != null) invTotal(inv, noted.internalName) else 0
        if (haveUnnoted.toLong() + haveNoted < qty) {
            mes("You only have ${haveUnnoted.toLong() + haveNoted} x ${itemName(type)}.")
            return
        }
        val fromUnnoted = minOf(haveUnnoted, qty)
        val fromNoted = qty - fromUnnoted
        if (fromUnnoted > 0 && invDel(inv, type.internalName, fromUnnoted).failure) {
            mes("Could not remove the items from your inventory.")
            return
        }
        if (noted != null && fromNoted > 0 && invDel(inv, noted.internalName, fromNoted).failure) {
            // Return what was already taken so a failed listing never costs the player items.
            invAdd(inv, type.internalName, fromUnnoted, strict = false)
            mes("Could not remove the items from your inventory.")
            return
        }
        val placement =
            GeState.book.placeSell(
                accountId = accountId,
                slot = slot,
                itemId = type.id,
                quantity = qty,
                pricePer = price,
                now = System.currentTimeMillis(),
            )
        persist(placement.touched, placement.trades)
        val name = itemName(type)
        if (placement.offer.state == GeOfferState.Completed) {
            val net = GeState.book.sellerProceeds(placement.offer)
            mes("Sell order filled instantly: $qty x $name. Collect $net gp via ::ge collect.")
        } else {
            mes("Sell offer placed in slot $slot: $qty x $name @ $price gp each.")
        }
    }

    private fun ProtectedAccess.listOffers() {
        val pending = GeState.book.pendingOffersForAccount(player.accountId).sortedBy { it.slot }
        if (pending.isEmpty()) {
            mes("You have no Grand Exchange offers.")
            return
        }
        mes("Your offers (slot | type | item | filled/total @ price | state):")
        for (offer in pending) {
            val name = itemName(item(offer.itemId))
            mes(
                "[${offer.slot}] ${offer.type} $name: " +
                    "${offer.itemsFilled}/${offer.quantity} @ ${offer.pricePer} gp | ${offer.state}"
            )
        }
    }

    private fun ProtectedAccess.cancel(parts: List<String>) {
        val slot = parts.getOrNull(1)?.toIntOrNull()
        if (slot == null) {
            mes("Usage: ::ge cancel <slot>")
            return
        }
        val accountId = player.accountId
        val offer = GeState.book.activeOffersForAccount(accountId).firstOrNull { it.slot == slot }
        if (offer == null) {
            mes("No active offer in slot $slot.")
            return
        }
        val snapshot = GeState.book.cancel(offer.id) ?: return
        persist(listOf(snapshot), emptyList())
        when (offer.type) {
            GeOfferType.Buy -> {
                val refund = GeState.book.buyerRefund(offer)
                mes("Offer cancelled. ::ge collect to reclaim $refund gp and any items.")
            }
            GeOfferType.Sell -> {
                mes("Offer cancelled. ::ge collect to reclaim your ${offer.remaining} items.")
            }
        }
    }

    /**
     * Hands back every finished offer that fits. Each offer is all-or-nothing: if its items or gold
     * do not fit, it stays uncollected (nothing is dropped on the floor or lost) and collection
     * stops there.
     */
    private fun ProtectedAccess.collect() {
        val accountId = player.accountId
        val finished =
            GeState.book.pendingOffersForAccount(accountId).filter {
                it.state != GeOfferState.Active
            }
        if (finished.isEmpty()) {
            mes("Nothing to collect at the Grand Exchange.")
            return
        }
        val coins = item(COINS) ?: return
        val collected = mutableListOf<GeOffer>()
        var goldOut = 0L
        var itemsOut = 0
        var blocked = false
        for (offer in finished) {
            val type = item(offer.itemId)
            val items = offer.collectableItems
            val gold = offer.collectableGold
            if (items > 0 && type == null) {
                logger.error { "GE offer ${offer.id} holds unknown item ${offer.itemId}" }
                continue
            }
            if (invCoinTotal().toLong() + gold > Int.MAX_VALUE) {
                blocked = true
                break
            }
            val noteItems = type != null && items > 1 && !type.isStackable && type.canCert
            if (type != null && items > 0 && invAdd(inv, type.internalName, items, cert = noteItems).failure) {
                blocked = true
                break
            }
            if (gold > 0 && invAdd(inv, coins.internalName, gold.toInt()).failure) {
                if (type != null && items > 0) {
                    val given = if (noteItems) certOf(type) ?: type else type
                    invDel(inv, given.internalName, items)
                }
                blocked = true
                break
            }
            GeState.book.markCollected(offer.id)?.let(collected::add)
            goldOut += gold
            itemsOut += items
        }
        persist(collected, emptyList())
        if (collected.isNotEmpty()) {
            mes("Collected: $goldOut gp, $itemsOut item(s).")
        }
        if (blocked) {
            mes("Not enough inventory space to collect everything - make room and ::ge collect.")
        }
    }

    private fun ProtectedAccess.price(parts: List<String>) {
        val itemId = parts.getOrNull(1)?.toIntOrNull()
        if (itemId == null) {
            mes("Usage: ::ge price <item id>")
            return
        }
        val requested = item(itemId)
        if (requested == null) {
            mes("Unknown item id: $itemId")
            return
        }
        val type = uncert(requested)
        val name = itemName(type)
        val playerUid = player.uid
        db.request(
            request = { conn -> GameDbResult.Ok(recentPrices(conn, type.id)) },
            response = response@{ result ->
                    val player = playerUid.resolve(playerList) ?: return@response
                    result.fold(
                        onOk = { rows ->
                            if (rows.isEmpty()) {
                                player.mes("No recent trades for $name.")
                            } else {
                                player.mes("Recent trades for $name:")
                                for ((pricePer, qty) in rows) {
                                    player.mes("  $qty x @ $pricePer gp")
                                }
                            }
                        },
                        onErr = { player.mes("Database error reading prices.") },
                    )
                },
        )
    }

    private fun itemName(type: ItemServerType?): String =
        type?.name?.takeIf(String::isNotBlank) ?: type?.internalName ?: "item"

    private fun item(id: Int): ItemServerType? = ServerCacheManager.getItem(id)

    /** The noted form of [type], or null when it has none. */
    private fun certOf(type: ItemServerType): ItemServerType? =
        if (type.canCert) ServerCacheManager.getItem(type.certlink) else null

    /**
     * Writes offer snapshots and trades in a single request, which the gateway runs as one
     * transaction. Offer upserts are revision-guarded and trade inserts are keyed by offer pair, so
     * reordered or retried requests are harmless.
     */
    private fun persist(offers: List<GeOffer>, trades: List<GeTrade>) {
        if (offers.isEmpty() && trades.isEmpty()) {
            return
        }
        db.request(
            request = { conn ->
                for (offer in offers) {
                    upsertOffer(conn, offer)
                }
                for (trade in trades) {
                    insertTrade(conn, trade)
                }
                GameDbResult.Ok(Unit)
            },
            response = { result ->
                result.fold(
                    onOk = {},
                    onErr = { err ->
                        logger.error {
                            "Failed to persist GE state (offers=${offers.map(GeOffer::id)}, " +
                                "trades=${trades.size}): $err"
                        }
                    },
                )
            },
        )
    }

    private fun restoreOffers() {
        if (GeState.ready || GeState.restoring) {
            return
        }
        GeState.restoring = true
        db.request(
            request = { conn ->
                migrate(conn)
                GameDbResult.Ok(loadOffers(conn) to maxOfferId(conn))
            },
            response = { result ->
                GeState.restoring = false
                result.fold(
                    onOk = { (offers, maxId) ->
                        for (offer in offers) {
                            GeState.book.restore(offer)
                        }
                        GeState.book.ensureNextIdAbove(maxId)
                        GeState.ready = true
                        logger.info { "Grand Exchange restored ${offers.size} offer(s)." }
                    },
                    onErr = { err ->
                        logger.error { "Failed to restore Grand Exchange offers: $err" }
                    },
                )
            },
        )
    }

    private fun migrate(conn: DatabaseConnection) {
        BeanlabSchema.migrate(
            conn,
            module = "ge",
            owner = GeCommands::class.java,
            files = listOf("migrations/V1__grand_exchange.sql"),
        )
    }

    private fun upsertOffer(conn: DatabaseConnection, offer: GeOffer) {
        val stmt =
            conn.prepareStatement(
                "INSERT INTO beanlab.ge_offers AS o " +
                    "(id, account_id, slot, type, item_id, quantity, remaining, price_per, " +
                    "gold_escrow, gold_spent, proceeds_gold, items_filled, tax_paid, state, revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET " +
                    "remaining = excluded.remaining, gold_spent = excluded.gold_spent, " +
                    "proceeds_gold = excluded.proceeds_gold, items_filled = excluded.items_filled, " +
                    "tax_paid = excluded.tax_paid, state = excluded.state, " +
                    "revision = excluded.revision, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE excluded.revision > o.revision"
            )
        stmt.use {
            it.setLong(1, offer.id)
            it.setInt(2, offer.accountId)
            it.setInt(3, offer.slot)
            it.setString(4, offer.type.dbValue)
            it.setInt(5, offer.itemId)
            it.setInt(6, offer.quantity)
            it.setInt(7, offer.remaining)
            it.setLong(8, offer.pricePer)
            it.setLong(9, offer.goldEscrow)
            it.setLong(10, offer.goldSpent)
            it.setLong(11, offer.proceedsGold)
            it.setInt(12, offer.itemsFilled)
            it.setLong(13, offer.taxPaid)
            it.setString(14, offer.state.dbValue)
            it.setLong(15, offer.revision)
            it.executeUpdate()
        }
    }

    private fun insertTrade(conn: DatabaseConnection, trade: GeTrade) {
        val stmt =
            conn.prepareStatement(
                "INSERT INTO beanlab.ge_history " +
                    "(item_id, quantity, price_per, gross, tax, buyer_account_id, " +
                    "seller_account_id, buy_offer_id, sell_offer_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
            )
        stmt.use {
            it.setInt(1, trade.itemId)
            it.setInt(2, trade.qty)
            it.setLong(3, trade.pricePer)
            it.setLong(4, trade.gross)
            it.setLong(5, trade.tax)
            it.setInt(6, trade.buyerAccountId)
            it.setInt(7, trade.sellerAccountId)
            it.setLong(8, trade.buyOfferId)
            it.setLong(9, trade.sellOfferId)
            it.executeUpdate()
        }
    }

    private fun loadOffers(conn: DatabaseConnection): List<GeOffer> {
        val stmt =
            conn.prepareStatement(
                "SELECT id, account_id, slot, type, item_id, quantity, remaining, price_per, " +
                    "gold_escrow, gold_spent, proceeds_gold, items_filled, tax_paid, state, " +
                    "(EXTRACT(EPOCH FROM created_at) * 1000)::BIGINT, revision FROM beanlab.ge_offers " +
                    "WHERE state IN ('ACTIVE', 'COMPLETED', 'CANCELLED')"
            )
        val offers = mutableListOf<GeOffer>()
        stmt.use {
            it.executeQuery().use { rs ->
                while (rs.next()) {
                    offers +=
                        GeOffer(
                            id = rs.getLong(1),
                            accountId = rs.getInt(2),
                            slot = rs.getInt(3),
                            type = GeOfferType.fromDb(rs.getString(4)),
                            itemId = rs.getInt(5),
                            quantity = rs.getInt(6),
                            remaining = rs.getInt(7),
                            pricePer = rs.getLong(8),
                            goldEscrow = rs.getLong(9),
                            goldSpent = rs.getLong(10),
                            proceedsGold = rs.getLong(11),
                            itemsFilled = rs.getInt(12),
                            taxPaid = rs.getLong(13),
                            state = GeOfferState.fromDb(rs.getString(14)),
                            createdAtMs = rs.getLong(15),
                            revision = rs.getLong(16),
                        )
                }
            }
        }
        return offers
    }

    private fun maxOfferId(conn: DatabaseConnection): Long {
        val stmt = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM beanlab.ge_offers")
        return stmt.use { it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
    }

    private fun recentPrices(conn: DatabaseConnection, itemId: Int): List<Pair<Long, Int>> {
        val stmt =
            conn.prepareStatement(
                "SELECT price_per, quantity FROM beanlab.ge_history WHERE item_id = ? " +
                    "ORDER BY traded_at DESC, id DESC LIMIT 5"
            )
        val rows = mutableListOf<Pair<Long, Int>>()
        stmt.use {
            it.setInt(1, itemId)
            it.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += rs.getLong(1) to rs.getInt(2)
                }
            }
        }
        return rows
    }

    private companion object {
        const val COINS = 995
    }
}
