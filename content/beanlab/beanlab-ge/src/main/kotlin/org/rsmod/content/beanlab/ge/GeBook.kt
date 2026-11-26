package org.rsmod.content.beanlab.ge

/**
 * Pure Grand Exchange matching engine: price-time priority, resting-offer pricing, 2% seller tax
 * per item (exempt below 100 gp per item, capped at 5m per item), escrowed buy offers with refunds,
 * partial fills.
 */
public object GeTypes {
    public const val TAX_RATE_NUMERATOR: Long = 2
    public const val TAX_RATE_DENOMINATOR: Long = 100
    public const val TAX_CAP_PER_ITEM: Long = 5_000_000L
    public const val TAX_EXEMPT_BELOW_PRICE: Long = 100L
    public const val MAX_SLOTS_PER_ACCOUNT: Int = 8

    /** Every offer total (and therefore every collectable gold amount) must fit in a coin stack. */
    public const val MAX_OFFER_VALUE: Long = Int.MAX_VALUE.toLong()

    /** Seller tax for a fill of [qty] items at [pricePer] each. */
    public fun taxOn(qty: Int, pricePer: Long): Long {
        if (pricePer < TAX_EXEMPT_BELOW_PRICE) {
            return 0L
        }
        val perItem = minOf(pricePer * TAX_RATE_NUMERATOR / TAX_RATE_DENOMINATOR, TAX_CAP_PER_ITEM)
        return perItem * qty
    }
}

/** [dbValue] is what the `ge_offers.type` column stores (see the V7 CHECK constraint). */
public enum class GeOfferType(public val dbValue: String) {
    Buy("BUY"),
    Sell("SELL");

    public companion object {
        public fun fromDb(value: String): GeOfferType =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown GE offer type: $value")
    }
}

/** [dbValue] is what the `ge_offers.state` column stores (see the V7 CHECK constraint). */
public enum class GeOfferState(public val dbValue: String) {
    Active("ACTIVE"),
    Completed("COMPLETED"),
    Cancelled("CANCELLED"),
    Collected("COLLECTED");

    public companion object {
        public fun fromDb(value: String): GeOfferState =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown GE offer state: $value")
    }
}

public data class GeOffer(
    public val id: Long,
    public val accountId: Int,
    public val slot: Int,
    public val type: GeOfferType,
    public val itemId: Int,
    public val quantity: Int,
    public var remaining: Int,
    public val pricePer: Long,
    public var goldEscrow: Long,
    public var goldSpent: Long = 0L,
    public var proceedsGold: Long = 0L,
    public var itemsFilled: Int = 0,
    public var taxPaid: Long = 0L,
    public var state: GeOfferState = GeOfferState.Active,
    public val createdAtMs: Long,
    /**
     * Bumped on every mutation. Persistence only overwrites a stored row with a higher revision, so
     * out-of-order or retried database writes can never roll an offer back to a stale state.
     */
    public var revision: Long = 0L,
) {
    /** Gold this offer can hand back on collection: unspent escrow (buy) or net proceeds (sell). */
    public val collectableGold: Long
        get() =
            when (type) {
                GeOfferType.Buy -> goldEscrow - goldSpent
                GeOfferType.Sell -> proceedsGold
            }

    /** Items this offer can hand back on collection: fills (buy) or unsold stock (sell). */
    public val collectableItems: Int
        get() =
            when (type) {
                GeOfferType.Buy -> itemsFilled
                GeOfferType.Sell -> remaining
            }
}

public data class GeTrade(
    public val itemId: Int,
    public val qty: Int,
    public val pricePer: Long,
    public val gross: Long,
    public val tax: Long,
    public val netToSeller: Long,
    public val buyerAccountId: Int,
    public val sellerAccountId: Int,
    public val buyOfferId: Long,
    public val sellOfferId: Long,
)

/**
 * Result of placing an offer. [touched] holds immutable snapshots of every offer whose state
 * changed (the new offer plus each resting counterparty), taken at placement time so they can be
 * persisted off the game thread without racing later mutations.
 */
public data class GePlacement(
    public val offer: GeOffer,
    public val trades: List<GeTrade>,
    public val touched: List<GeOffer>,
)

public class GeBook {
    private val offers = LinkedHashMap<Long, GeOffer>()
    private var nextId = 1L

    public fun activeOffersForAccount(accountId: Int): List<GeOffer> =
        offers.values.filter { it.accountId == accountId && it.state == GeOfferState.Active }

    /** All offers for an account that still occupy a slot (not yet collected). */
    public fun pendingOffersForAccount(accountId: Int): List<GeOffer> =
        offers.values.filter { it.accountId == accountId && it.state != GeOfferState.Collected }

    public fun offer(id: Long): GeOffer? = offers[id]

    public fun activeOffersForItem(itemId: Int): List<GeOffer> =
        offers.values.filter { it.itemId == itemId && it.state == GeOfferState.Active }

    /** Snapshot of every offer currently tracked by the book. */
    public fun allOffers(): List<GeOffer> = offers.values.toList()

    /** Lowest free slot for [accountId], or `null` if every slot holds an uncollected offer. */
    public fun freeSlot(accountId: Int): Int? {
        val used = pendingOffersForAccount(accountId).mapTo(HashSet()) { it.slot }
        return (0 until GeTypes.MAX_SLOTS_PER_ACCOUNT).firstOrNull { it !in used }
    }

    /** Restores an offer persisted in a previous server run. */
    public fun restore(offer: GeOffer) {
        if (offer.state != GeOfferState.Collected) {
            offers[offer.id] = offer
        }
        ensureNextIdAbove(offer.id)
    }

    public fun ensureNextIdAbove(id: Long) {
        if (id >= nextId) {
            nextId = id + 1
        }
    }

    public fun placeBuy(
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GePlacement = place(GeOfferType.Buy, accountId, slot, itemId, quantity, pricePer, now)

    public fun placeSell(
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GePlacement = place(GeOfferType.Sell, accountId, slot, itemId, quantity, pricePer, now)

    private fun place(
        type: GeOfferType,
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GePlacement {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(pricePer > 0) { "pricePer must be positive: $pricePer" }
        require(quantity.toLong() * pricePer <= GeTypes.MAX_OFFER_VALUE) {
            "offer value exceeds ${GeTypes.MAX_OFFER_VALUE}: $quantity x $pricePer"
        }
        val offer = createOffer(type, accountId, slot, itemId, quantity, pricePer, now)
        offers[offer.id] = offer
        val counterparties = mutableListOf<GeOffer>()
        val trades = match(offer, counterparties)
        finishIfDone(offer)
        val touched = (listOf(offer) + counterparties).map { it.copy() }
        return GePlacement(offer, trades, touched)
    }

    private fun createOffer(
        type: GeOfferType,
        accountId: Int,
        slot: Int,
        itemId: Int,
        quantity: Int,
        pricePer: Long,
        now: Long,
    ): GeOffer =
        GeOffer(
            id = nextId++,
            accountId = accountId,
            slot = slot,
            type = type,
            itemId = itemId,
            quantity = quantity,
            remaining = quantity,
            pricePer = pricePer,
            goldEscrow = if (type == GeOfferType.Buy) quantity.toLong() * pricePer else 0L,
            createdAtMs = now,
        )

    /** Cancels an active offer and returns a snapshot of it, or `null` if it was not active. */
    public fun cancel(offerId: Long): GeOffer? {
        val offer = offers[offerId] ?: return null
        if (offer.state != GeOfferState.Active) {
            return null
        }
        offer.state = GeOfferState.Cancelled
        offer.revision++
        return offer.copy()
    }

    /**
     * Marks a completed or cancelled offer as collected, drops it from the book and returns a
     * snapshot of it, or `null` if it is unknown or still active.
     */
    public fun markCollected(offerId: Long): GeOffer? {
        val offer = offers[offerId] ?: return null
        if (offer.state == GeOfferState.Active || offer.state == GeOfferState.Collected) {
            return null
        }
        offer.state = GeOfferState.Collected
        offer.revision++
        offers.remove(offerId)
        return offer.copy()
    }

    /** Buyer refund: escrow not spent on fills. */
    public fun buyerRefund(offer: GeOffer): Long = offer.goldEscrow - offer.goldSpent

    /** Seller collect: net proceeds after tax. */
    public fun sellerProceeds(offer: GeOffer): Long = offer.proceedsGold

    private fun match(incoming: GeOffer, counterparties: MutableList<GeOffer>): List<GeTrade> {
        val trades = mutableListOf<GeTrade>()
        val resting =
            offers.values
                .filter {
                    it.state == GeOfferState.Active &&
                        it.id != incoming.id &&
                        it.itemId == incoming.itemId &&
                        it.type != incoming.type
                }
                .sortedWith(
                    compareBy(
                        { if (incoming.type == GeOfferType.Buy) it.pricePer else -it.pricePer },
                        { it.createdAtMs },
                        { it.id },
                    )
                )
        for (other in resting) {
            if (incoming.remaining == 0) {
                break
            }
            val crosses =
                if (incoming.type == GeOfferType.Buy) {
                    other.pricePer <= incoming.pricePer
                } else {
                    other.pricePer >= incoming.pricePer
                }
            if (!crosses) {
                break
            }
            val qty = minOf(incoming.remaining, other.remaining)
            val pricePer = other.pricePer
            val gross = qty.toLong() * pricePer
            val tax = GeTypes.taxOn(qty, pricePer)
            val net = gross - tax
            val buyer = if (incoming.type == GeOfferType.Buy) incoming else other
            val seller = if (incoming.type == GeOfferType.Buy) other else incoming
            buyer.goldSpent += gross
            seller.proceedsGold += net
            seller.taxPaid += tax
            for (side in arrayOf(buyer, seller)) {
                side.remaining -= qty
                side.itemsFilled += qty
                side.revision++
            }
            trades.add(
                GeTrade(
                    itemId = incoming.itemId,
                    qty = qty,
                    pricePer = pricePer,
                    gross = gross,
                    tax = tax,
                    netToSeller = net,
                    buyerAccountId = buyer.accountId,
                    sellerAccountId = seller.accountId,
                    buyOfferId = buyer.id,
                    sellOfferId = seller.id,
                )
            )
            finishIfDone(other)
            counterparties += other
        }
        return trades
    }

    private fun finishIfDone(offer: GeOffer) {
        if (offer.remaining == 0 && offer.state == GeOfferState.Active) {
            offer.state = GeOfferState.Completed
            offer.revision++
        }
    }
}
