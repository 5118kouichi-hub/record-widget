package com.example.recordwidget

import android.app.Activity
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Shared lists: "みんなのリスト" under the player on the home screen (most liked first), the
 * member's own lists on the 音声一覧 page, and the nickname row in settings.
 *
 * Listening adds the list's tracks to the 音声一覧 as streamed tracks (ids "cloud-<id>"), so the
 * widget, the floating record and the player all work with them unchanged.
 */
class SharedListsUi(
    private val activity: Activity,
    private val screen: MainScreen,
    private val s: UiStyle,
    private val onLibraryChanged: () -> Unit,
) {
    private val cloud = Cloud(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var profile: Cloud.Profile? = null
    private var profileLoaded = false
    private var likes: Set<String> = emptySet()
    private var askedName = false

    private val publicCard = screen.card()
    private val publicStatus = screen.footer(0)
    private val mineCard = screen.card()
    private val nameText = screen.text("", 16f, s.text)
    private val nameSub = screen.text("", 12.5f, s.sub)

    /** Goes under the player on the home screen. */
    val homeSection: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(screen.caption(R.string.shared_public))
        addView(publicCard)
        addView(publicStatus)
    }

    /** Goes on the 音声一覧 page. */
    val mineSection: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(screen.caption(R.string.shared_mine))
        addView(mineCard)
        addView(screen.footer(R.string.shared_mine_footer))
    }

    /** Goes in settings: the nickname everyone sees. */
    val nameSection: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(screen.caption(R.string.shared_name_caption))
        val row = screen.rowBase().apply {
            addView(screen.icon(R.drawable.ic_settings, s.accent))
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(nameText)
                    addView(nameSub)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            screen.pressable(this)
            setOnClickListener { askName(cancelable = true) }
        }
        addView(screen.card(row))
    }

    init {
        showPublic(null, "読み込み中…")
        showMine(emptyList())
        showName()
    }

    // ---- loading -------------------------------------------------------------------------

    fun refresh() {
        // UI tests run without a network; never create test members on the real project.
        if (android.os.Build.FINGERPRINT == "robolectric") return
        background({
            val p = cloud.myProfile()
            val pub = cloud.publicLists()
            val mine = if (p != null) cloud.myLists() else emptyList()
            val liked = if (p != null) cloud.myLikes() else emptySet()
            Loaded(p, pub, mine, liked)
        }, onError = { msg ->
            showPublic(null, "みんなのリストを読み込めませんでした（$msg）")
        }) { d ->
            profile = d.profile
            profileLoaded = true
            likes = d.liked
            showName()
            showPublic(d.public, null)
            showMine(d.mine)
            if (d.profile == null && !askedName) {
                askedName = true
                askName(cancelable = true)
            }
        }
    }

    private class Loaded(
        val profile: Cloud.Profile?,
        val public: List<Cloud.SharedList>,
        val mine: List<Cloud.SharedList>,
        val liked: Set<String>,
    )

    private fun showName() {
        val p = profile
        nameText.text = p?.name ?: "未登録"
        nameSub.text = when {
            !profileLoaded -> "読み込み中…"
            p == null -> "タップしてニックネームを決める"
            p.isAdmin -> "管理者 ・ タップで変更"
            else -> "タップで変更"
        }
    }

    private fun showPublic(lists: List<Cloud.SharedList>?, status: String?) {
        publicCard.removeAllViews()
        publicStatus.text = status ?: ""
        publicStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        if (lists == null) {
            publicCard.visibility = View.GONE
            return
        }
        publicCard.visibility = View.VISIBLE
        if (lists.isEmpty()) {
            publicCard.addView(emptyRow("まだ公開されているリストはありません"))
            return
        }
        lists.forEachIndexed { i, l ->
            if (i > 0) publicCard.addView(screen.separator())
            publicCard.addView(publicRow(l, i))
        }
    }

    private fun showMine(lists: List<Cloud.SharedList>) {
        mineCard.removeAllViews()
        mineCard.addView(
            screen.rowBase().apply {
                addView(screen.icon(R.drawable.ic_add, s.accent))
                addView(screen.text("新しいリストを作る", 16f, s.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                screen.pressable(this)
                setOnClickListener { startCreate() }
            },
        )
        for (l in lists) {
            mineCard.addView(screen.separator())
            mineCard.addView(
                screen.rowBase().apply {
                    addView(titleBlock(l.title, "${l.trackCount}曲 ・ ${if (l.isPublic) "公開中" else "非公開"} ・ ♥ ${l.likes}"))
                    screen.pressable(this)
                    setOnClickListener { openList(l) }
                },
            )
        }
    }

    private fun emptyRow(msg: String) = screen.text(msg, 14f, s.sub).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(18), dp(16), dp(18))
    }

    private fun titleBlock(title: String, sub: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(
            screen.text(title, 16f, s.text).apply {
                typeface = s.serifBold
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        addView(screen.text(sub, 12.5f, s.sub))
    }

    private fun publicRow(l: Cloud.SharedList, rank: Int): View = screen.rowBase().apply {
        addView(
            screen.text("%02d".format(rank + 1), 13f, s.accent).apply {
                typeface = s.serifBold
                minWidth = dp(28)
            },
        )
        addView(titleBlock(l.title, "${l.ownerName.ifEmpty { "だれか" }} ・ ${l.trackCount}曲"))
        addView(likeButton(l))
        screen.pressable(this)
        setOnClickListener { openList(l) }
    }

    private fun likeButton(l: Cloud.SharedList): View {
        val liked = l.id in likes
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            addView(screen.text(if (liked) "♥" else "♡", 20f, if (liked) s.accent else s.sub))
            addView(screen.text(" ${l.likes}", 14f, s.sub))
            contentDescription = if (liked) "いいねを取り消す" else "いいね"
            isClickable = true
            setOnClickListener { toggleLike(l) }
        }
    }

    // ---- actions -------------------------------------------------------------------------

    private fun needName(then: () -> Unit) {
        if (profile != null) then() else askName(cancelable = true, then = then)
    }

    private fun toggleLike(l: Cloud.SharedList) = needName {
        val on = l.id !in likes
        background({ cloud.setLiked(l.id, on) }) { refresh() }
    }

    fun askName(cancelable: Boolean, then: (() -> Unit)? = null) {
        val edit = EditText(activity).apply {
            setText(profile?.name.orEmpty())
            hint = "例：こういち"
            filters = arrayOf(InputFilter.LengthFilter(20))
            setSingleLine()
        }
        val message = if (profile == null) {
            "みんなのリストに表示される名前です。いちばん最初に登録した人が管理者になります。"
        } else {
            "みんなのリストに表示される名前です。"
        }
        AlertDialog.Builder(activity)
            .setTitle("ニックネーム")
            .setMessage(message)
            .setView(padded(edit))
            .setCancelable(cancelable)
            .setNegativeButton("あとで", null)
            .setPositiveButton("決定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton toast("名前を入れてください")
                background({
                    cloud.setName(name)
                    cloud.myProfile()
                }) { p ->
                    profile = p
                    profileLoaded = true
                    showName()
                    toast(if (p?.isAdmin == true) "登録しました（あなたが管理者です）" else "登録しました")
                    then?.invoke()
                    refresh()
                }
            }
            .show()
    }

    private fun startCreate() = needName {
        val tracks = try {
            TrackStore(activity.filesDir).all().filter { !it.isRemote }
        } catch (e: Exception) {
            emptyList()
        }
        if (tracks.isEmpty()) return@needName toast("動画から取り出した音声がまだありません（URLの音声は共有できません）")
        val store = TrackStore(activity.filesDir)
        val title = EditText(activity).apply {
            hint = "リストの名前"
            filters = arrayOf(InputFilter.LengthFilter(40))
            setSingleLine()
        }
        val publicSwitch = SwitchCompat(activity).apply {
            text = "みんなに公開する"
            textSize = 15f
            isChecked = true
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(s.accent, s.separator))
        }
        val checks = tracks.map { t ->
            CheckBox(activity).apply {
                text = t.title
                textSize = 15f
                isChecked = true
                isEnabled = store.fileFor(t).length() <= Cloud.MAX_FILE_BYTES
                if (!isEnabled) {
                    isChecked = false
                    text = "${t.title}（50MBを超えるので不可）"
                }
            }
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(publicSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(screen.text("入れる曲（今の並び順で入ります）", 13f, s.sub).apply { setPadding(0, dp(14), 0, dp(4)) })
            checks.forEach { addView(it) }
        }
        AlertDialog.Builder(activity)
            .setTitle("新しいリスト")
            .setView(ScrollView(activity).apply { addView(padded(col)) })
            .setNegativeButton("やめる", null)
            .setPositiveButton("アップロード") { _, _ ->
                val name = title.text.toString().trim()
                val chosen = tracks.filterIndexed { i, _ -> checks[i].isChecked }
                when {
                    name.isEmpty() -> toast("リストの名前を入れてください")
                    chosen.isEmpty() -> toast("曲を1つ以上選んでください")
                    else -> upload(name, publicSwitch.isChecked, chosen.map { it.title to store.fileFor(it) })
                }
            }
            .show()
    }

    private fun upload(name: String, public: Boolean, items: List<Pair<String, java.io.File>>) {
        val total = items.sumOf { it.second.length() }
        val progress = screen.text("準備中…", 15f, s.text).apply { setPadding(dp(24), dp(20), dp(24), dp(8)) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("アップロード中")
            .setView(progress)
            .setCancelable(false)
            .show()
        background({
            val used = cloud.storageUsed()
            if (used + total > Cloud.FREE_BYTES) {
                throw Cloud.CloudException(
                    "保存場所がいっぱいです（使用 ${mb(used)}MB / 1000MB、このリストは ${mb(total)}MB）。古いリストを消してください",
                )
            }
            cloud.createList(name, public, items) { i ->
                activity.runOnUiThread { progress.text = "${i + 1} / ${items.size} 曲目を送っています…" }
            }
            used + total
        }, onError = { msg ->
            dialog.dismiss()
            alert("アップロードできませんでした", msg)
        }) { usedAfter ->
            dialog.dismiss()
            toast(if (public) "公開しました" else "非公開で保存しました")
            if (usedAfter > Cloud.FREE_BYTES * 8 / 10) {
                alert("保存場所が残り少なくなっています", "使用 ${mb(usedAfter)}MB / 1000MB です。")
            }
            refresh()
        }
    }

    private fun openList(l: Cloud.SharedList) {
        val loading = AlertDialog.Builder(activity).setMessage("読み込み中…").show()
        background({ cloud.tracks(l.id) }, onError = { msg ->
            loading.dismiss()
            toast("読み込めませんでした（$msg）")
        }) { tracks ->
            loading.dismiss()
            val mine = l.ownerId == cloud.userId
            val canDelete = mine || profile?.isAdmin == true
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(screen.text("${l.ownerName.ifEmpty { "だれか" }} ・ ♥ ${l.likes}", 13f, s.sub).apply { setPadding(0, 0, 0, dp(8)) })
                tracks.forEachIndexed { i, t ->
                    addView(screen.text("%02d  %s".format(i + 1, t.title), 15f, s.text).apply { setPadding(0, dp(6), 0, dp(6)) })
                }
                if (tracks.isEmpty()) addView(screen.text("曲がありません", 14f, s.sub))
                if (tracks.isNotEmpty()) {
                    addView(
                        screen.text("⤓ 好きな曲を選んでダウンロード", 15f, s.accent).apply {
                            setPadding(0, dp(14), 0, dp(6))
                            isClickable = true
                            setOnClickListener { chooseDownloads(l, tracks) }
                        },
                    )
                }
                if (mine) {
                    addView(
                        SwitchCompat(activity).apply {
                            text = "みんなに公開する"
                            textSize = 15f
                            isChecked = l.isPublic
                            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                            trackTintList = ColorStateList(states, intArrayOf(s.accent, s.separator))
                            setOnCheckedChangeListener { _, on ->
                                background({ cloud.setPublic(l.id, on) }) {
                                    toast(if (on) "公開しました" else "非公開にしました")
                                    refresh()
                                }
                            }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) },
                    )
                }
            }
            val b = AlertDialog.Builder(activity)
                .setTitle(l.title)
                .setView(ScrollView(activity).apply { addView(padded(col)) })
                .setNegativeButton("閉じる", null)
            if (tracks.isNotEmpty()) b.setPositiveButton("聴く") { _, _ -> listen(l, tracks) }
            if (canDelete) b.setNeutralButton("削除") { _, _ -> confirmDelete(l, mine) }
            b.show()
        }
    }

    private fun confirmDelete(l: Cloud.SharedList, mine: Boolean) {
        AlertDialog.Builder(activity)
            .setTitle("「${l.title}」を削除")
            .setMessage(if (mine) "アップロードした音声も消えます。" else "管理者として、${l.ownerName}さんのリストを削除します。")
            .setNegativeButton("やめる", null)
            .setPositiveButton("削除") { _, _ ->
                background({ cloud.deleteList(l.id) }) {
                    toast("削除しました")
                    refresh()
                }
            }
            .show()
    }

    /** Pick tracks of a shared list and save them on this phone (play offline, no data use). */
    private fun chooseDownloads(l: Cloud.SharedList, tracks: List<Cloud.SharedTrack>) {
        val have = TrackStore(activity.filesDir).all().map { it.id }.toSet()
        val checked = BooleanArray(tracks.size) { DL_PREFIX + tracks[it].id !in have }
        val names = tracks.map { t -> if (DL_PREFIX + t.id in have) "${t.title}（保存済み）" else t.title }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("ダウンロードする曲")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("やめる", null)
            .setPositiveButton("ダウンロード") { _, _ ->
                val chosen = tracks.filterIndexed { i, t -> checked[i] && DL_PREFIX + t.id !in have }
                if (chosen.isEmpty()) toast("新しく保存する曲がありません") else download(l, chosen)
            }
            .show()
    }

    private fun download(l: Cloud.SharedList, tracks: List<Cloud.SharedTrack>) {
        toast("${tracks.size}曲をダウンロードしています…")
        background({
            val store = TrackStore(activity.filesDir)
            var done = 0
            for (t in tracks) {
                val id = DL_PREFIX + t.id
                val ext = t.path.substringAfterLast('.', "m4a")
                val fileName = "$id.$ext"
                val out = java.io.File(activity.filesDir, "tracks/$fileName")
                cloud.download(t.path, out)
                store.add(Track(id, t.title, fileName))
                // the streamed copy (from 聴く) is no longer needed
                if (store.all().any { it.id == CLOUD_PREFIX + t.id }) store.remove(CLOUD_PREFIX + t.id)
                done++
            }
            done
        }, onError = { msg ->
            onLibraryChanged()
            PlaybackService.reload(activity)
            alert("ダウンロードが途中で止まりました", msg)
        }) { n ->
            onLibraryChanged()
            PlaybackService.reload(activity)
            toast("「${l.title}」から${n}曲を保存しました（音声一覧）")
        }
    }

    /** Adds the list to the 音声一覧 as streamed tracks and starts it if nothing is playing. */
    private fun listen(l: Cloud.SharedList, tracks: List<Cloud.SharedTrack>) {
        toast("準備しています…")
        background({
            val signed = tracks.map { t -> t to cloud.signedUrl(t.path) }
            val store = TrackStore(activity.filesDir)
            for ((t, url) in signed) store.putRemote(CLOUD_PREFIX + t.id, t.title, url)
            CLOUD_PREFIX + tracks.first().id
        }) { firstId ->
            onLibraryChanged()
            if (!PlaybackService.isPlaying) {
                PlaybackState.saveResume(activity, firstId, 0)
                PlaybackService.reload(activity)
                PlaybackService.command(activity, PlaybackService.ACTION_TOGGLE)
                toast("「${l.title}」を再生します")
            } else {
                PlaybackService.reload(activity)
                toast("「${l.title}」を音声一覧に追加しました")
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun <T> background(work: () -> T, onError: ((String) -> Unit)? = null, done: (T) -> Unit) {
        io.execute {
            val result = runCatching(work)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                result.fold(done) { e ->
                    val msg = when (e) {
                        is Cloud.CloudException -> e.message ?: "エラー"
                        is java.io.IOException -> "通信できません。電波を確認してください"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    if (onError != null) onError(msg) else toast(msg)
                }
            }
        }
    }

    private fun padded(v: View): View = LinearLayout(activity).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun mb(bytes: Long) = bytes / 1_000_000

    companion object {
        const val CLOUD_PREFIX = "cloud-"
        const val DL_PREFIX = "dl-"
    }
}
