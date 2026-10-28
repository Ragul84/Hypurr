package com.ragul84.hypurr.data

/**
 * When to ask for a Play rating: after the 3rd finished task, never twice within 120 days, and
 * never again once rated or after "No thanks" twice. Pure so it can be tested.
 */
class RatePolicy(private val store: Persistence, private val now: () -> Long = System::currentTimeMillis) {
    private val finished: Set<String> get() = store.read(SEEN)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** Records a finished task; true when the prompt should show now. */
    fun taskFinished(taskId: String): Boolean {
        val seen = finished
        if (taskId in seen) return false
        store.write(SEEN, (seen + taskId).toList().takeLast(50).joinToString(","))
        return shouldAsk(seen.size + 1)
    }

    private fun shouldAsk(count: Int): Boolean {
        if (store.read(RATED) == "1" || (store.read(DECLINED)?.toIntOrNull() ?: 0) >= 2) return false
        val last = store.read(ASKED)?.toLongOrNull()
        return count >= MIN_TASKS && (last == null || now() - last >= GAP_MS)
    }

    fun asked() = store.write(ASKED, now().toString())
    fun rated() = store.write(RATED, "1")
    fun declined() = store.write(DECLINED, ((store.read(DECLINED)?.toIntOrNull() ?: 0) + 1).toString())

    companion object {
        const val MIN_TASKS = 3
        const val GAP_MS = 120L * 24 * 60 * 60 * 1000
        private const val SEEN = "rate.finished"
        private const val ASKED = "rate.askedAt"
        private const val RATED = "rate.rated"
        private const val DECLINED = "rate.declined"
    }
}
