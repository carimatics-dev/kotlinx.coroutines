/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("FlowKt")

package kotlinx.coroutines.flow

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.internal.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlinx.coroutines.flow.internal.unsafeFlow as flow

/**
 * Emits all elements from the given [channel] to this flow collector and [cancels][cancel] (consumes)
 * the channel afterwards. If you need to iterate over the channel without consuming it,
 * a regular `for` loop should be used instead.
 *
 * This function provides a more efficient shorthand for `channel.consumeEach { value -> emit(value) }`.
 * See [consumeEach][ReceiveChannel.consumeEach].
 */
@ExperimentalCoroutinesApi
public suspend fun <T> FlowCollector<T>.emitAll(channel: ReceiveChannel<T>) {
    // Manually inlined "consumeEach" implementation that does not use iterator but works via "receiveOrClosed".
    // It has smaller and more efficient spilled state which also allows to implement a manual kludge to
    // fix retention of the last emitted value.
    // See https://youtrack.jetbrains.com/issue/KT-16222
    // See https://github.com/Kotlin/kotlinx.coroutines/issues/1333
    var cause: Throwable? = null
    try {
        while (true) {
            // :KLUDGE: This "run" call is resolved to an extension function "run" and forces the size of
            // spilled state to increase by an additional slot, so there are 4 object local variables spilled here
            // which makes the size of spill state equal to the 4 slots that are spilled around subsequent "emit"
            // call, ensuring that the previously emitted value is not retained in the state while receiving
            // the next one.
            //     L$0 <- this
            //     L$1 <- channel
            //     L$2 <- cause
            //     L$3 <- this$run (actually equal to this)
            val result = run { channel.receiveOrClosed() }
            if (result.isClosed) {
                result.closeCause?.let { throw it }
                break // returns normally when result.closeCause == null
            }
            // result is spilled here to the coroutine state and retained after the call, even though
            // it is not actually needed in the next loop iteration.
            //     L$0 <- this
            //     L$1 <- channel
            //     L$2 <- cause
            //     L$3 <- result
            emit(result.value)
        }
    } catch (e: Throwable) {
        cause = e
        throw e
    } finally {
        channel.cancelConsumed(cause)
    }
}

/**
 * Represents the given receive channel as a hot flow and [consumes][ReceiveChannel.consume] the channel
 * on the first collection from this flow. The resulting flow can be collected just once and throws
 * [IllegalStateException] when trying to collect it more than once.
 *
 * ### Cancellation semantics
 *
 * 1) Flow consumer is cancelled when the original channel is cancelled.
 * 2) Flow consumer completes normally when the original channel completes (~is closed) normally.
 * 3) If the flow consumer fails with an exception, channel is cancelled.
 *
 * ### Operator fusion
 *
 * Adjacent applications of [flowOn], [buffer], [conflate], and [produceIn] to the result of `consumeAsFlow` are fused.
 * In particular, [produceIn] returns the original channel (but throws [IllegalStateException] on repeated calls).
 * Calls to [flowOn] have generally no effect, unless [buffer] is used to explicitly request buffering.
 */
@FlowPreview
public fun <T> ReceiveChannel<T>.consumeAsFlow(): Flow<T> = ConsumeAsFlow(this)

/**
 * Represents an existing [channel] as [ChannelFlow] implementation.
 * It fuses with subsequent [flowOn] operators, but for the most part ignores the specified context.
 * However, additional [buffer] calls cause a separate buffering channel to be created and that is where
 * the context might play a role, because it is used by the producing coroutine.
 */
private class ConsumeAsFlow<T>(
    private val channel: ReceiveChannel<T>,
    context: CoroutineContext = EmptyCoroutineContext,
    capacity: Int = Channel.OPTIONAL_CHANNEL
) : ChannelFlow<T>(context, capacity) {
    private val consumed = atomic(false)

    private fun markConsumed() =
        check(!consumed.getAndSet(true)) { "ReceiveChannel.consumeAsFlow can be collected just once" }
    
    override fun create(context: CoroutineContext, capacity: Int): ChannelFlow<T> =
        ConsumeAsFlow(channel, context, capacity)

    override suspend fun collectTo(scope: ProducerScope<T>) =
        SendingCollector(scope).emitAll(channel) // use efficient channel receiving code from emitAll

    override fun broadcastImpl(scope: CoroutineScope, start: CoroutineStart): BroadcastChannel<T> {
        markConsumed() // fail fast on repeated attempt to collect it
        return super.broadcastImpl(scope, start)
    }

    override fun produceImpl(scope: CoroutineScope): ReceiveChannel<T> {
        markConsumed() // fail fast on repeated attempt to collect it
        return if (capacity == Channel.OPTIONAL_CHANNEL) {
            channel // direct
        } else
            super.produceImpl(scope) // extra buffering channel
    }

    override suspend fun collect(collector: FlowCollector<T>) {
        if (capacity == Channel.OPTIONAL_CHANNEL) {
            markConsumed()
            collector.emitAll(channel) // direct
        } else {
            super.collect(collector) // extra buffering channel, produceImpl will mark it as consumed
        }
    }

    override fun additionalToStringProps(): String = "channel=$channel, "
}

/**
 * Represents the given broadcast channel as a hot flow.
 * Every flow collector will trigger a new broadcast channel subscription.
 *
 * ### Cancellation semantics
 * 1) Flow consumer is cancelled when the original channel is cancelled.
 * 2) Flow consumer completes normally when the original channel completes (~is closed) normally.
 * 3) If the flow consumer fails with an exception, subscription is cancelled.
 */
@FlowPreview
public fun <T> BroadcastChannel<T>.asFlow(): Flow<T> = flow {
    emitAll(openSubscription())
}

/**
 * Creates a [broadcast] coroutine that collects the given flow.
 *
 * This transformation is **stateful**, it launches a [broadcast] coroutine
 * that collects the given flow and thus resulting channel should be properly closed or cancelled.
 *
 * A channel with [default][Channel.Factory.BUFFERED] buffer size is created.
 * Use [buffer] operator on the flow before calling `produce` to specify a value other than
 * default and to control what happens when data is produced faster than it is consumed,
 * that is to control backpressure behavior.
 */
@FlowPreview
public fun <T> Flow<T>.broadcastIn(
    scope: CoroutineScope,
    start: CoroutineStart = CoroutineStart.LAZY
): BroadcastChannel<T> =
    asChannelFlow().broadcastImpl(scope, start)

/**
 * Creates a [produce] coroutine that collects the given flow.
 *
 * This transformation is **stateful**, it launches a [produce] coroutine
 * that collects the given flow and thus resulting channel should be properly closed or cancelled.
 *
 * A channel with [default][Channel.Factory.BUFFERED] buffer size is created.
 * Use [buffer] operator on the flow before calling `produce` to specify a value other than
 * default and to control what happens when data is produced faster than it is consumed,
 * that is to control backpressure behavior.
 */
@FlowPreview
public fun <T> Flow<T>.produceIn(
    scope: CoroutineScope
): ReceiveChannel<T> =
    asChannelFlow().produceImpl(scope)
