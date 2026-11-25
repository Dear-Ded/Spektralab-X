package com.dearded.spektralab.advisor.network

import com.dearded.spektralab.Edit
import com.dearded.spektralab.advisor.domain.*

/** Synthetic parameters only; testing never reads the open editor/photo or a local profile. */
internal object AdvisorConnectionProbe {
    val request get() = AdviceRequest.from(EditSnapshot(Edit.NONE, RequestTicket(0, 0, 0, 0, 0, 0)),
        AdviceConstraints(emptyMap(), 0), "Connection check with synthetic parameters. Return no_change.")
}
