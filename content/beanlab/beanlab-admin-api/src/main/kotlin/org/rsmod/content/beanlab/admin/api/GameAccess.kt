package org.rsmod.content.beanlab.admin.api

import jakarta.inject.Inject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold

/**
 * Bridges HTTP worker threads to the game. RsMod only runs DB responses on the game thread, so an
 * empty DB request is the way to hop onto it: [onGameThread] waits for that callback and runs the
 * block there. [query] runs SQL on the DB worker pool in its own transaction and returns the
 * result.
 *
 * Both block the calling HTTP thread for at most [TIMEOUT_SECONDS]; never call them from the game
 * thread itself.
 */
public class GameAccess @Inject constructor(private val db: GameDbManager) {
    public fun <T> onGameThread(block: () -> T): T {
        val future = CompletableFuture<T>()
        db.request(
            request = { GameDbResult.Ok(Unit) },
            response = { result ->
                result.fold(
                    onOk = {
                        try {
                            future.complete(block())
                        } catch (error: Throwable) {
                            future.completeExceptionally(error)
                        }
                    },
                    onErr = { future.completeExceptionally(busy()) },
                )
            },
        )
        return await(future)
    }

    public fun <T> query(block: (DatabaseConnection) -> T): T {
        val future = CompletableFuture<T>()
        db.request(
            request = { connection -> GameDbResult.Ok(block(connection)) },
            response = { result ->
                result.fold(
                    onOk = { future.complete(it) },
                    onErr = { future.completeExceptionally(busy()) },
                )
            },
        )
        return await(future)
    }

    /** Queues a write and returns at once. Used for audit rows, which must not slow a request. */
    public fun fireAndForget(block: (DatabaseConnection) -> Unit) {
        db.request(request = { connection -> GameDbResult.Ok(block(connection)) }, response = {})
    }

    private fun <T> await(future: CompletableFuture<T>): T =
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause as? ApiError ?: ApiError(500, "The server hit an error.")
        } catch (error: TimeoutException) {
            throw busy()
        }

    private fun busy() = ApiError(503, "The game is busy. Try again in a moment.")

    private companion object {
        private const val TIMEOUT_SECONDS = 8L
    }
}
