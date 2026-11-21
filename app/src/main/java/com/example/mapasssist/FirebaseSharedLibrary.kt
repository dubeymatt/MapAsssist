package com.example.mapasssist

import android.content.Context
import android.util.Base64
import com.example.mapasssist.data.AppDatabase
import com.example.mapasssist.data.TextFile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

/**
 * Live shared data backed by Firestore.  Unlike the retired Drive CSV system,
 * each card and activity item is stored independently, so simultaneous edits
 * do not replace an entire library file.
 */
object FirebaseSharedLibrary {
    private const val PREFS = "map_assist"
    private const val LIBRARY_ID = "firebase_shared_library_id"
    private const val ROLE = "firebase_shared_library_role"
    private const val STATE = "shared_library_state"
    private const val APPLYING_REMOTE = "firebase_applying_remote"
    private const val INVITE_CODE = "firebase_shared_invite_code"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private var listeners = mutableListOf<ListenerRegistration>()

    data class Member(val id: String, val name: String, val email: String, val role: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun db() = FirebaseFirestore.getInstance()
    private fun uid(): String = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Sign in with Google first" }
    private fun libraryId(context: Context): String = requireNotNull(prefs(context).getString(LIBRARY_ID, null)) { "No shared library joined" }
    private fun cardId(mapNo: String) = Base64.encodeToString(mapNo.trim().uppercase().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun library(context: Context) = db().collection("map_assist_libraries").document(libraryId(context))
    fun isConfigured(context: Context) = !prefs(context).getString(LIBRARY_ID, null).isNullOrBlank()
    fun isHost(context: Context) = prefs(context).getString(ROLE, "") == "host"
    fun state(context: Context) = prefs(context).getString(STATE, "").orEmpty()
    fun signedIn() = FirebaseAuth.getInstance().currentUser != null
    private fun setState(context: Context, value: String) = prefs(context).edit().putString(STATE, value).apply()

    suspend fun create(context: Context): String {
        val owner = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Google first")
        setState(context, "syncing")
        val id = UUID.randomUUID().toString()
        val root = db().collection("map_assist_libraries").document(id)
        root.set(mapOf("hostUid" to owner.uid, "createdAt" to System.currentTimeMillis(), "updatedAt" to System.currentTimeMillis())).await()
        root.collection("members").document(owner.uid).set(memberMap(owner.displayName, owner.email, "Host")).await()
        prefs(context).edit().putString(LIBRARY_ID, id).putString(ROLE, "host").apply()
        syncAll(context)
        start(context)
        return id
    }

    suspend fun join(context: Context, code: String) {
        val invite = db().collection("map_assist_invites").document(code.trim().uppercase()).get().await()
        val id = invite.getString("libraryId") ?: error("That invite code is not valid")
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Google first")
        db().collection("map_assist_libraries").document(id).collection("members").document(user.uid)
            .set(memberMap(user.displayName, user.email, "Member") + mapOf("inviteCode" to code.trim().uppercase()), SetOptions.merge()).await()
        db().collection("map_assist_libraries").document(id).collection("activity").add(
            mapOf("time" to System.currentTimeMillis(), "person" to user.displayName.orEmpty().ifBlank { user.email.orEmpty() }, "summary" to "Joined the shared library")
        ).await()
        prefs(context).edit().putString(LIBRARY_ID, id).putString(ROLE, "member").putString(STATE, "syncing").apply()
        start(context)
    }

    suspend fun createInvite(context: Context): String {
        check(isHost(context)) { "Only the host can invite members" }
        prefs(context).getString(INVITE_CODE, null)?.let { return it }
        val code = buildString { repeat(8) { append(ALPHABET[SecureRandom().nextInt(ALPHABET.length)]) } }
        db().collection("map_assist_invites").document(code).set(mapOf("libraryId" to libraryId(context), "createdAt" to System.currentTimeMillis(), "createdBy" to uid())).await()
        prefs(context).edit().putString(INVITE_CODE, code).apply()
        return code
    }

    suspend fun members(context: Context): List<Member> = library(context).collection("members").get().await().documents.map {
        Member(it.id, it.getString("name").orEmpty().ifBlank { "Member" }, it.getString("email").orEmpty(), it.getString("role").orEmpty().ifBlank { "Member" })
    }.sortedBy { it.name.lowercase() }

    suspend fun removeMember(context: Context, member: Member) {
        check(isHost(context)) { "Only the host can remove members" }
        check(member.id != uid()) { "The host cannot remove themselves" }
        library(context).collection("activity").add(mapOf("time" to System.currentTimeMillis(), "person" to FirebaseAuth.getInstance().currentUser?.displayName.orEmpty().ifBlank { "Host" }, "summary" to "Removed ${member.name} from the shared library")).await()
        library(context).collection("members").document(member.id).delete().await()
    }

    suspend fun transferHost(context: Context, member: Member) {
        check(isHost(context)) { "Only the host can transfer ownership" }
        check(member.id != uid()) { "Choose another member" }
        val root = library(context)
        root.set(mapOf("hostUid" to member.id, "updatedAt" to System.currentTimeMillis()), SetOptions.merge()).await()
        root.collection("members").document(member.id).set(mapOf("role" to "Host"), SetOptions.merge()).await()
        root.collection("members").document(uid()).set(mapOf("role" to "Member"), SetOptions.merge()).await()
        root.collection("activity").add(mapOf("time" to System.currentTimeMillis(), "person" to "Host", "summary" to "Transferred host role to ${member.name}")).await()
        prefs(context).edit().putString(ROLE, "member").apply()
    }

    suspend fun deleteLibrary(context: Context) {
        check(isHost(context)) { "Only the host can delete the library" }
        val root = library(context)
        suspend fun removeCollection(name: String) { root.collection(name).get().await().documents.forEach { it.reference.delete().await() } }
        removeCollection("cards"); removeCollection("activity"); removeCollection("system")
        root.collection("members").get().await().documents.filter { it.id != uid() }.forEach { it.reference.delete().await() }
        root.delete().await()
        root.collection("members").document(uid()).delete().await()
        leave(context)
    }

    suspend fun refresh(context: Context) {
        if (!isConfigured(context)) return
        val root = library(context)
        val cards = root.collection("cards").get(com.google.firebase.firestore.Source.SERVER).await().documents.mapNotNull(::cardFrom)
        applyRemoteCards(context, cards)
        val ledger = root.collection("system").document("ledger").get(com.google.firebase.firestore.Source.SERVER).await()
        val assignments = (ledger.get("assignments") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::assignmentFrom) }.orEmpty()
        LedgerStore.replace(context, assignments, notifySharedSync = false)
        setState(context, "synced")
    }

    suspend fun clearActivity(context: Context) {
        check(isHost(context)) { "Only the host can clear shared activity" }
        library(context).collection("activity").get().await().documents.forEach { it.reference.delete().await() }
        SharedActivityStore.replaceFromRemote(context, emptyList())
    }

    fun leave(context: Context) {
        stop()
        prefs(context).edit().remove(LIBRARY_ID).remove(ROLE).remove(INVITE_CODE).remove(STATE).apply()
    }

    /** Signing out pauses live access but deliberately remembers the library. */
    fun signedOut(context: Context) { stop(); if (isConfigured(context)) setState(context, "signed_out") }

    fun start(context: Context) {
        if (!isConfigured(context) || listeners.isNotEmpty()) return
        if (!signedIn()) { setState(context, "signed_out"); return }
        val root = library(context)
        listeners += root.collection("members").document(uid()).addSnapshotListener { snapshot, error ->
            if (error != null) { setState(context, "failed"); return@addSnapshotListener }
            if (snapshot != null && !snapshot.exists()) {
                stop()
                prefs(context).edit().remove(LIBRARY_ID).remove(ROLE).remove(INVITE_CODE).putString(STATE, "removed").apply()
            }
        }
        listeners += root.collection("cards").addSnapshotListener { snapshot, error ->
            if (error != null) { setState(context, "failed"); return@addSnapshotListener }
            val cards = snapshot?.documents?.mapNotNull(::cardFrom).orEmpty()
            if (cards.isNotEmpty() || snapshot?.isEmpty == true) applyRemoteCards(context, cards)
        }
        listeners += root.collection("system").document("ledger").addSnapshotListener { snapshot, error ->
            if (error != null) { setState(context, "failed"); return@addSnapshotListener }
            val values = snapshot?.get("assignments") as? List<*> ?: emptyList<Any>()
            val assignments = values.mapNotNull { value -> (value as? Map<*, *>)?.let(::assignmentFrom) }
            if (snapshot?.exists() == true) LedgerStore.replace(context, assignments, notifySharedSync = false)
        }
        listeners += root.collection("activity").orderBy("time", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(120).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            SharedActivityStore.replaceFromRemote(context, snapshot?.documents?.mapNotNull(::activityFrom).orEmpty())
        }
        listeners += root.addSnapshotListener { snapshot, error ->
            if (error != null) { setState(context, "failed"); return@addSnapshotListener }
            if (snapshot?.exists() == true) setState(context, "synced")
        }
    }

    fun stop() { listeners.forEach { it.remove() }; listeners.clear() }

    fun queueSync(context: Context) {
        if (!isConfigured(context) || prefs(context).getBoolean(APPLYING_REMOTE, false)) return
        setState(context, "pending")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { syncAll(context); setState(context, "synced") }.onFailure { setState(context, "failed") }
        }
    }

    suspend fun publishCard(context: Context, card: TextFile) {
        if (!isConfigured(context)) return
        library(context).collection("cards").document(cardId(card.mapNo)).set(cardMap(card), SetOptions.merge()).await()
        setState(context, "synced")
    }

    suspend fun deleteCard(context: Context, mapNo: String) {
        if (!isConfigured(context)) return
        library(context).collection("cards").document(cardId(mapNo)).delete().await()
        setState(context, "synced")
    }

    suspend fun replaceCards(context: Context, cards: List<TextFile>) {
        if (!isConfigured(context)) return
        val collection = library(context).collection("cards")
        collection.get().await().documents.forEach { it.reference.delete().await() }
        cards.forEach { collection.document(cardId(it.mapNo)).set(cardMap(it)).await() }
        setState(context, "synced")
    }

    suspend fun syncAll(context: Context) {
        if (!isConfigured(context)) return
        val root = library(context)
        val cards = AppDatabase.getDatabase(context).textFileDao().getAllForBackup()
        cards.forEach { card -> root.collection("cards").document(cardId(card.mapNo)).set(cardMap(card), SetOptions.merge()).await() }
        root.collection("system").document("ledger").set(mapOf("assignments" to LedgerStore.read(context).map(::assignmentMap), "updatedAt" to System.currentTimeMillis()), SetOptions.merge()).await()
        root.set(mapOf("updatedAt" to System.currentTimeMillis()), SetOptions.merge()).await()
    }

    fun recordActivity(context: Context, entry: SharedActivity) {
        if (!isConfigured(context) || prefs(context).getBoolean(APPLYING_REMOTE, false)) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { library(context).collection("activity").add(mapOf("time" to entry.time, "person" to entry.person, "summary" to entry.summary)).await() }
        }
    }

    private fun applyRemoteCards(context: Context, cards: List<TextFile>) {
        CoroutineScope(Dispatchers.IO).launch {
            prefs(context).edit().putBoolean(APPLYING_REMOTE, true).apply()
            try { AppDatabase.getDatabase(context).textFileDao().replaceAll(cards) }
            finally { prefs(context).edit().putBoolean(APPLYING_REMOTE, false).apply() }
        }
    }
    private fun memberMap(name: String?, email: String?, role: String) = mapOf("name" to name.orEmpty(), "email" to email.orEmpty(), "role" to role)
    private fun cardMap(card: TextFile) = mapOf("title" to card.title, "content" to card.content, "mapNo" to card.mapNo, "entriesJson" to card.entriesJson, "lastModified" to card.lastModified)
    private fun cardFrom(doc: DocumentSnapshot): TextFile? = doc.getString("mapNo")?.let { TextFile(title = doc.getString("title").orEmpty(), content = doc.getString("content").orEmpty(), mapNo = it, entriesJson = doc.getString("entriesJson").orEmpty().ifBlank { "[]" }, lastModified = doc.getLong("lastModified") ?: 0L) }
    private fun assignmentMap(value: LedgerAssignment) = mapOf("mapNo" to value.mapNo, "title" to value.cardTitle, "assignedTo" to value.assignedTo, "assignedOn" to value.assignedOn, "returnedOn" to value.returnedOn, "notes" to value.notes)
    private fun assignmentFrom(map: Map<*, *>): LedgerAssignment? {
        val mapNo = map["mapNo"] as? String ?: return null
        return LedgerAssignment(mapNo, map["title"] as? String ?: "", map["assignedTo"] as? String ?: "", map["assignedOn"] as? String ?: "", map["returnedOn"] as? String ?: "", map["notes"] as? String ?: "")
    }
    private fun activityFrom(doc: DocumentSnapshot): SharedActivity? {
        val time = doc.getLong("time") ?: return null
        return SharedActivity(time, doc.getString("person").orEmpty(), doc.getString("summary").orEmpty())
    }
}
