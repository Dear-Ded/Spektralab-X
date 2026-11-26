package com.dearded.spektralab.advisor.session

import com.dearded.spektralab.advisor.domain.AdviceResult

/** Task-local only. Accepting a question never dispatches a request or creates a preference. */
class LocalQuestionPolicy {
    private var count = 0
    var question: AdviceResult.Question? = null
        private set

    fun offer(next: AdviceResult.Question): Boolean {
        if (count >= 2) { question = null; return false }
        count++
        question = next
        return true
    }

    fun answer(text: String): TaskClarification {
        val pending = checkNotNull(question)
        require(pending.kind != "choice" || pending.options.any { it.id == text })
        val result = TaskClarification(pending, text)
        question = null
        return result
    }

    fun retry(pending: AdviceResult.Question) { question = pending }

    fun reset() { count = 0; question = null }
}
