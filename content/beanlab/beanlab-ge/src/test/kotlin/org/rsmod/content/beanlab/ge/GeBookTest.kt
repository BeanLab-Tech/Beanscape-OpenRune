package org.rsmod.content.beanlab.ge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeBookTest {
    @Test
    fun crossingBuyFillsRestingSellAtRestingPrice() {
        val book = GeBook()
        val sell =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 4151,
                quantity = 2,
                pricePer = 1_500_000,
                now = 1,
            )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 2_000_000,
                now = 2,
            )
        assertEquals(1, buy.trades.size)
        assertEquals(1_500_000, buy.trades[0].pricePer)
        assertEquals(2, buy.trades[0].sellerAccountId)
        assertEquals(500_000L, book.buyerRefund(buy.offer))
        val tax = 1_500_000L * 2 / 100
        assertEquals(1_500_000L - tax, book.sellerProceeds(sell.offer))
    }

    @Test
    fun partialFillLeavesRemainderActive() {
        val book = GeBook()
        book.placeSell(accountId = 2, slot = 0, itemId = 995, quantity = 10, pricePer = 1, now = 1)
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 995,
                quantity = 4,
                pricePer = 1,
                now = 2,
            )
        assertEquals(4, buy.offer.itemsFilled)
        assertEquals(GeOfferState.Completed, buy.offer.state)
        val sell = book.activeOffersForItem(995).single()
        assertEquals(6, sell.remaining)
        assertEquals(GeOfferState.Active, sell.state)
    }

    @Test
    fun nonCrossingOffersDoNotTrade() {
        val book = GeBook()
        book.placeSell(
            accountId = 2,
            slot = 0,
            itemId = 4151,
            quantity = 1,
            pricePer = 3_000_000,
            now = 1,
        )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 2_000_000,
                now = 2,
            )
        assertTrue(buy.trades.isEmpty())
        assertEquals(1, buy.offer.remaining)
        assertEquals(0L, buy.offer.goldSpent)
    }

    @Test
    fun cheapItemsAreTaxExemptAndCapAppliesPerItem() {
        val book = GeBook()
        val sellCheap =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 319,
                quantity = 100,
                pricePer = 10,
                now = 1,
            )
        book.placeBuy(accountId = 1, slot = 0, itemId = 319, quantity = 100, pricePer = 10, now = 2)
        assertEquals(0L, sellCheap.offer.taxPaid)

        // 2% of 1b is 20m per item, capped at 5m per item - not 5m for the whole fill.
        val book2 = GeBook()
        val sellBig =
            book2.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 1,
                quantity = 2,
                pricePer = 1_000_000_000,
                now = 1,
            )
        book2.placeBuy(
            accountId = 1,
            slot = 0,
            itemId = 1,
            quantity = 2,
            pricePer = 1_000_000_000,
            now = 2,
        )
        assertEquals(2 * GeTypes.TAX_CAP_PER_ITEM, sellBig.offer.taxPaid)
        assertEquals(2_000_000_000L - 2 * GeTypes.TAX_CAP_PER_ITEM, sellBig.offer.proceedsGold)
    }

    @Test
    fun priceTimePriority() {
        val book = GeBook()
        book.placeSell(accountId = 3, slot = 0, itemId = 2, quantity = 1, pricePer = 120, now = 1)
        book.placeSell(accountId = 4, slot = 0, itemId = 2, quantity = 1, pricePer = 100, now = 2)
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 2,
                quantity = 1,
                pricePer = 200,
                now = 3,
            )
        assertEquals(4, buy.trades[0].sellerAccountId)
        assertEquals(100, buy.trades[0].pricePer)
    }

    @Test
    fun placementSnapshotsEveryTouchedOfferIncludingPartiallyFilledRestingOffers() {
        val book = GeBook()
        val sell =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 4151,
                quantity = 5,
                pricePer = 100,
                now = 1,
            )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 2,
                pricePer = 100,
                now = 2,
            )

        // The resting sell is still ACTIVE but partially filled; it must be persisted too, or a
        // restart would restore it with its full quantity (duplicating items) and no proceeds.
        val touchedSell = buy.touched.single { it.id == sell.offer.id }
        assertEquals(GeOfferState.Active, touchedSell.state)
        assertEquals(3, touchedSell.remaining)
        assertTrue(touchedSell.proceedsGold > 0)
        assertTrue(touchedSell.revision > sell.touched.single().revision)

        // Snapshots are copies: later mutations must not leak into already-queued writes.
        book.placeBuy(accountId = 3, slot = 0, itemId = 4151, quantity = 3, pricePer = 100, now = 3)
        assertEquals(3, touchedSell.remaining)
        assertEquals(0, book.offer(sell.offer.id)!!.remaining)
    }

    @Test
    fun tradesRecordTheMatchedOfferPair() {
        val book = GeBook()
        val sell =
            book.placeSell(
                accountId = 2,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 100,
                now = 1,
            )
        val buy =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 100,
                now = 2,
            )
        val trade = buy.trades.single()
        assertEquals(buy.offer.id, trade.buyOfferId)
        assertEquals(sell.offer.id, trade.sellOfferId)
    }

    @Test
    fun uncollectedOffersOccupySlotsUntilCollected() {
        val book = GeBook()
        repeat(GeTypes.MAX_SLOTS_PER_ACCOUNT) { i ->
            val slot = book.freeSlot(accountId = 1)!!
            assertEquals(i, slot)
            book.placeBuy(
                accountId = 1,
                slot = slot,
                itemId = 4151,
                quantity = 1,
                pricePer = 1,
                now = 1,
            )
        }
        assertNull(book.freeSlot(accountId = 1))

        val first = book.pendingOffersForAccount(1).first()
        assertNull(book.markCollected(first.id), "active offers cannot be collected")
        book.cancel(first.id)
        assertNull(book.freeSlot(accountId = 1), "cancelled offers hold their slot until collected")

        val collected = book.markCollected(first.id)!!
        assertEquals(GeOfferState.Collected, collected.state)
        assertNull(book.offer(first.id), "collected offers leave the in-memory book")
        assertEquals(first.slot, book.freeSlot(accountId = 1))
    }

    @Test
    fun restoredIdsAreNeverReused() {
        val book = GeBook()
        book.ensureNextIdAbove(41)
        val placed =
            book.placeBuy(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 1,
                pricePer = 1,
                now = 1,
            )
        assertEquals(42, placed.offer.id)
    }

    @Test
    fun rejectsOffersWhoseTotalCannotBeCollectedAsCoins() {
        val book = GeBook()
        assertFailsWith<IllegalArgumentException> {
            book.placeSell(
                accountId = 1,
                slot = 0,
                itemId = 4151,
                quantity = 2,
                pricePer = Int.MAX_VALUE.toLong(),
                now = 1,
            )
        }
    }

    @Test
    fun enumsRoundTripThroughTheStoredDatabaseValues() {
        for (type in GeOfferType.entries) {
            assertEquals(type, GeOfferType.fromDb(type.dbValue))
        }
        for (state in GeOfferState.entries) {
            assertEquals(state, GeOfferState.fromDb(state.dbValue))
        }
        // Values written by the V7-era code must keep loading.
        assertEquals(GeOfferType.Buy, GeOfferType.fromDb("BUY"))
        assertEquals(GeOfferState.Cancelled, GeOfferState.fromDb("CANCELLED"))
    }
}
