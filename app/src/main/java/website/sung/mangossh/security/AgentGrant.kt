package website.sung.mangossh.security

import website.sung.mangossh.domain.HostAgentPolicy

/** A connection-scoped grant is invalidated by any lock, even if the app was already unlocked again. */
internal class AgentGrant(
    private val policy: HostAgentPolicy,
    private val access: AppAccessState,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var generation = access.generation
    private var grantedAt = clockNanos()

    @Synchronized fun authorize(confirm: () -> Boolean): Boolean {
        if (access.locked.value) return false
        val currentGeneration = access.generation
        val expired = policy.authorizationSeconds > 0 && clockNanos() - grantedAt >= policy.authorizationSeconds * 1_000_000_000L
        if (policy.confirmEachSignature || expired || generation != currentGeneration) {
            if (!confirm() || access.locked.value || currentGeneration != access.generation) return false
            generation = currentGeneration
            grantedAt = clockNanos()
        }
        return !access.locked.value && generation == access.generation
    }
}
