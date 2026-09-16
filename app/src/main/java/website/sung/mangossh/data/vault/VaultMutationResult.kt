package website.sung.mangossh.data.vault

/** Outcome of an atomic vault mutation. Failed writes retain the last committed snapshot. */
sealed interface VaultMutationResult {
    data object Success : VaultMutationResult
    data class ReferencedBy(val profileIds: List<String>) : VaultMutationResult
    data object Invalid : VaultMutationResult
    data object NotReady : VaultMutationResult
    data object StorageFailure : VaultMutationResult
    val isSuccess: Boolean get() = this == Success
}
