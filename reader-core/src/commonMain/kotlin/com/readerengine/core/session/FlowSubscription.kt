package com.readerengine.core.session

/**
 * Assinatura cancelável para observação de `StateFlow` e `SharedFlow` a partir de Swift/Objective-C
 * (iOS) ou código imperativo sem necessidade de gerenciar `CoroutineScope` manualmente no host.
 */
public fun interface FlowSubscription {
    /** Cancela a coleta ativa deste fluxo. */
    public fun cancel()
}
