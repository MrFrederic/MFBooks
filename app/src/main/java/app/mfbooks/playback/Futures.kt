package app.mfbooks.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ponytail: the two bridges we need, instead of pulling in kotlinx-coroutines-guava.

fun <T> CoroutineScope.future(block: suspend CoroutineScope.() -> T): ListenableFuture<T> {
    val f = SettableFuture.create<T>()
    launch {
        try { f.set(block()) } catch (e: CancellationException) { f.cancel(false); throw e } catch (e: Throwable) { f.setException(e) }
    }
    return f
}

suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { c ->
    addListener({
        try { c.resume(get()) } catch (e: ExecutionException) { c.resumeWithException(e.cause ?: e) } catch (e: Throwable) { c.resumeWithException(e) }
    }, MoreExecutors.directExecutor())
    c.invokeOnCancellation { cancel(false) }
}
