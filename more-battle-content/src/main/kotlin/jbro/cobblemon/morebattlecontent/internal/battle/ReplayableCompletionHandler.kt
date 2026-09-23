package jbro.cobblemon.morebattlecontent.internal.battle

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Attaches an exactly-once completion callback and replays a completion that won the race before
 * registration. Registering first closes the gap between the completion check and callback install;
 * the atomic gate then collapses a concurrent callback and the replay into one delivery.
 */
internal fun <T> attachReplayableCompletionHandler(
    completion: T,
    register: ((T) -> Unit) -> Unit,
    isComplete: () -> Boolean,
    handler: (T) -> Unit,
) {
    val delivered = AtomicBoolean(false)
    val deliverOnce: (T) -> Unit = { completed ->
        if (delivered.compareAndSet(false, true)) handler(completed)
    }
    register(deliverOnce)
    if (isComplete()) deliverOnce(completion)
}
