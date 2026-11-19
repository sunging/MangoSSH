package website.sung.mangossh.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import website.sung.mangossh.core.MangoLog
import website.sung.mangossh.core.MangoLogEvent
import website.sung.mangossh.data.drafts.RemoteDraft
import website.sung.mangossh.data.drafts.RemoteDraftCodec
import website.sung.mangossh.data.drafts.RemoteDraftStore
import website.sung.mangossh.session.AtomicReplaceUnavailableException
import website.sung.mangossh.session.EditableRemoteText
import website.sung.mangossh.session.MAX_REMOTE_PREVIEW_BYTES
import website.sung.mangossh.session.MetadataPreservationException
import website.sung.mangossh.session.RemoteOperationException
import website.sung.mangossh.session.RemoteOperationFailure
import website.sung.mangossh.session.SourceChangedException
import website.sung.mangossh.session.TextDiff

/** Identifies one editing instance even across successive opens of the same path and session. */
internal data class RemoteEditorIdentity(val sessionId: String, val path: String, val instance: Long)

internal enum class DraftPersistence { NONE, PENDING, WRITING, STORED, FAILED }

private const val DRAFT_SAVE_DELAY_MILLIS = 1_500L

/** Owns editor jobs on the UI dispatcher and serializes device draft I/O in submission order. */
internal class RemoteEditorCoordinator(
    private val scope: CoroutineScope,
    private val drafts: RemoteDraftStore,
    private val readRemote: suspend (String, String) -> EditableRemoteText,
    private val writeRemote: suspend (String, EditableRemoteText, String, String?, Boolean) -> Unit,
    private val canOpen: (String) -> Boolean,
    private val onOpenFailed: () -> Unit,
    private val onSaved: () -> Unit,
) {
    private val mutableState = MutableStateFlow<RemoteEditorUiState?>(null)
    val state = mutableState.asStateFlow()
    private val storeMutex = Mutex()
    private var nextInstance = 0L
    private var opening: RemoteEditorIdentity? = null
    private var openJob: Job? = null
    private var reloadJob: Job? = null
    private var actionJob: Job? = null
    private var reviewJob: Job? = null
    private var debounce: Job? = null
    private var deletingSlot = false

    fun open(sessionId: String, path: String, profileId: String?) {
        if (state.value != null || !canOpen(sessionId)) return
        cancelOpen()
        val request = RemoteEditorIdentity(sessionId, path, ++nextInstance)
        opening = request
        openJob = scope.launch {
            try {
                val source = readRemote(sessionId, path)
                val stored = profileId?.let { id -> store { load(id, source.path) }.getOrNull() }
                val recovered = stored?.takeIf { it.text != source.text }?.let {
                    RecoveredDraft(it.text, it.savedAtEpochMillis, !it.baseDigest.contentEquals(source.digest),
                        changes(source.text, it.text))
                }
                ensureActive()
                if (opening != request || !canOpen(sessionId)) return@launch
                val opened = RemoteEditorUiState(sessionId, source, profileId = profileId, recovered = recovered,
                    instance = request.instance)
                mutableState.value = opened
                if (stored != null && recovered == null) {
                    persist(opened)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (opening == request && canOpen(sessionId)) onOpenFailed()
            } finally {
                if (opening == request) opening = null
            }
        }
    }

    fun cancelOpen() {
        opening = null
        openJob?.cancel()
        openJob = null
    }

    fun cancelReads() {
        cancelOpen()
        if (!deletingSlot && reloadJob?.isActive == true) {
            reloadJob?.cancel()
            mutableState.value = state.value?.copy(busy = false)
        }
    }

    fun change(text: String) {
        val current = state.value ?: return
        if (current.busy || current.recovered != null || text == current.draft) return
        val size = if (text.length <= RemoteDraftCodec.MAX_TEXT_BYTES) current.source.encodedSize(text) else Int.MAX_VALUE
        val reducingLegacyDraft = current.encodedBytes > MAX_REMOTE_PREVIEW_BYTES && size < current.encodedBytes &&
            text.encodeToByteArray().size <= RemoteDraftCodec.MAX_TEXT_BYTES
        if (size > MAX_REMOTE_PREVIEW_BYTES && !reducingLegacyDraft) {
            mutableState.value = current.copy(inputRejected = true)
            return
        }
        reviewJob?.cancel()
        val next = current.copy(draft = text, revision = current.revision + 1, encodedBytes = size,
            inputRejected = false, review = null, draftPersistence = DraftPersistence.PENDING)
        mutableState.value = next
        debounce?.cancel()
        debounce = scope.launch {
            delay(DRAFT_SAVE_DELAY_MILLIS)
            persist(next)
        }
    }

    /** Publishes the newest revision immediately on backgrounding and connection loss. */
    fun flushDraft() {
        debounce?.cancel()
        val current = state.value ?: return
        if (deletingSlot) return
        if (current.draftPersistence == DraftPersistence.PENDING || current.draftPersistence == DraftPersistence.FAILED)
            persist(current)
    }

    fun onLocked() {
        cancelReads()
        reviewJob?.cancel()
        // The lock screen hides the editor immediately. Keep the accepted text in memory,
        // including when the device store cannot save it at the time of locking.
        mutableState.value = state.value?.copy(review = null)
        flushDraft()
    }

    fun onDisconnect(sessionId: String) {
        if (opening?.sessionId == sessionId) cancelOpen()
        val current = state.value?.takeIf { it.sessionId == sessionId } ?: return
        if (!deletingSlot) {
            actionJob?.cancel()
            reloadJob?.cancel()
        }
        reviewJob?.cancel()
        mutableState.value = current.copy(busy = current.busy && deletingSlot, disconnected = true, review = null)
        flushDraft()
    }

    fun restore() {
        val current = state.value ?: return
        val recovered = current.recovered ?: return
        if (current.busy) return
        mutableState.value = current.copy(draft = recovered.text, recovered = null, revision = current.revision + 1,
            encodedBytes = current.source.encodedSize(recovered.text), draftPersistence = DraftPersistence.STORED,
            lastDraftSavedAt = recovered.savedAtEpochMillis)
    }

    fun discardRecovered() {
        val current = state.value ?: return
        if (current.recovered == null || current.busy) return
        discard(current) { it.copy(recovered = null, busy = false, draftPersistence = DraftPersistence.NONE, lastDraftSavedAt = null) }
    }

    fun close(discardConfirmed: Boolean, keepSavedDraft: Boolean) {
        val current = state.value ?: return
        if (current.busy) return
        if (current.draft != current.source.text) {
            if (keepSavedDraft && current.draftPersistence == DraftPersistence.STORED) {
                hide()
            } else if (discardConfirmed) {
                discard(current) { null }
            }
        } else if (current.draftPersistence == DraftPersistence.NONE || current.recovered != null) {
            hide()
        } else if (discardConfirmed) {
            discard(current) { null }
        }
    }

    fun reload() {
        val current = state.value ?: return
        if (current.busy || current.disconnected) return
        mutableState.value = current.copy(busy = true, review = null, failed = false)
        reloadJob = scope.launch {
            try {
                val source = readRemote(current.sessionId, current.source.path)
                ensureActive()
                if (!isCurrent(current)) return@launch
                debounce?.cancel()
                val removed = deleteSlot(current)
                ensureActive()
                if (!isCurrent(current)) return@launch
                mutableState.value = if (removed) RemoteEditorUiState(current.sessionId, source, profileId = current.profileId,
                    instance = ++nextInstance, disconnected = state.value?.disconnected == true)
                else state.value?.copy(busy = false, draftPersistence = DraftPersistence.FAILED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(current)) mutableState.value = state.value?.copy(busy = false, failed = true)
            }
        }
    }

    fun review() {
        val current = state.value ?: return
        if (!current.canSave || current.review != null) return
        reviewJob?.cancel()
        reviewJob = scope.launch {
            val diff = changes(current.source.text, current.draft)
            ensureActive()
            if (isCurrent(current) && state.value?.canSave == true)
                mutableState.value = state.value?.copy(review = EditorReview(diff))
        }
    }

    fun dismissReview() {
        reviewJob?.cancel()
        mutableState.value = state.value?.copy(review = null)
    }

    fun save(alternateName: String?, allowDirect: Boolean) {
        val current = state.value ?: return
        if (!current.canSave) return
        dismissReview()
        mutableState.value = current.copy(busy = true, review = null, conflict = false, needsDirectApproval = false,
            failed = false, metadataFailure = false, invalidName = false)
        flushDraft()
        actionJob = scope.launch {
            val error = try {
                writeRemote(current.sessionId, current.source, current.draft, alternateName, allowDirect)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { error }
            ensureActive()
            if (!isCurrent(current)) return@launch
            if (error == null) {
                val removed = deleteSlot(current)
                ensureActive()
                if (!isCurrent(current)) return@launch
                if (removed) hide() else mutableState.value = state.value?.copy(busy = false, draftPersistence = DraftPersistence.FAILED)
                onSaved()
            } else {
                mutableState.value = state.value?.copy(busy = false, conflict = error is SourceChangedException,
                    needsDirectApproval = error is AtomicReplaceUnavailableException,
                    metadataFailure = error is MetadataPreservationException,
                    invalidName = (error as? RemoteOperationException)?.reason == RemoteOperationFailure.INVALID_NAME,
                    failed = error !is SourceChangedException && error !is AtomicReplaceUnavailableException &&
                        error !is MetadataPreservationException && error !is RemoteOperationException)
            }
        }
    }

    fun clearDrafts(done: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (store { clear() }.isSuccess) done()
        }
    }

    fun deleteProfile(profileId: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { store { deleteProfile(profileId) } }
    }

    private fun discard(current: RemoteEditorUiState, after: (RemoteEditorUiState) -> RemoteEditorUiState?) {
        debounce?.cancel()
        mutableState.value = current.copy(busy = true)
        actionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val removed = deleteSlot(current)
            if (!isCurrent(current)) return@launch
            val latest = state.value ?: return@launch
            if (removed) {
                mutableState.value = after(latest)
                if (state.value == null) hide()
            } else mutableState.value = latest.copy(busy = false, draftPersistence = DraftPersistence.FAILED)
        }
    }

    private fun hide() {
        debounce?.cancel()
        reviewJob?.cancel()
        mutableState.value = null
    }

    private fun persist(current: RemoteEditorUiState) {
        if (!isCurrent(current) || current.recovered != null) return
        mutableState.value = state.value?.copy(draftPersistence = DraftPersistence.WRITING)
        val dirty = current.draft != current.source.text
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val profile = current.profileId
            val result = store {
                if (!dirty) {
                    profile?.let { delete(it, current.source.path) }
                    null
                } else {
                    requireNotNull(profile)
                    save(RemoteDraft(profile, current.source.path, current.draft, current.source.digest, System.currentTimeMillis()))
                    System.currentTimeMillis()
                }
            }
            val latest = state.value?.takeIf { it.identity == current.identity } ?: return@launch
            val lastSavedAt = if (result.isSuccess) result.getOrNull() else latest.lastDraftSavedAt
            val persistence = when {
                latest.revision != current.revision -> latest.draftPersistence
                result.isFailure -> DraftPersistence.FAILED
                dirty -> DraftPersistence.STORED
                else -> DraftPersistence.NONE
            }
            mutableState.value = latest.copy(draftPersistence = persistence, lastDraftSavedAt = lastSavedAt)
        }
    }

    private fun isCurrent(snapshot: RemoteEditorUiState): Boolean =
        state.value?.let { it.identity == snapshot.identity && it.revision == snapshot.revision } == true

    private suspend fun deleteSlot(current: RemoteEditorUiState): Boolean {
        deletingSlot = true
        try {
            return store { current.profileId?.let { delete(it, current.source.path) } }.isSuccess
        } finally {
            deletingSlot = false
        }
    }

    private suspend fun changes(before: String, after: String) =
        withContext(Dispatchers.Default) { TextDiff.lines(before, after)?.let(TextDiff::condense) }

    private suspend fun <T> store(block: RemoteDraftStore.() -> T): Result<T> = storeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                Result.success(drafts.block())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MangoLog.warn(MangoLogEvent.EDITOR_DRAFT_STORE_FAILED, error)
                Result.failure(error)
            }
        }
    }
}
