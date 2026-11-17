# レコードウィジェット Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 動画から抽出した音声をホーム画面のレコード型ウィジェットから連続再生する Android アプリ。

**Architecture:** 単一モジュール `app`。データ層（Track/TrackStore, JSON）、抽出（AudioExtractor: MediaExtractor→MediaMuxer）、再生（PlaybackService: Media3 MediaSessionService + ExoPlayer）、ウィジェット（RecordWidget: AppWidgetProvider + RemoteViews）、UI（MainActivity: 一覧・取り込み・共有受け取り）。

**Tech Stack:** Kotlin 2.0, AGP 8.7, Gradle 8.14, androidx.media3 1.4.1, JUnit4。

**Spec:** `docs/2026-11-18-record-widget-design.md`

## Global Constraints
- minSdk 26, targetSdk 35, compileSdk 35。applicationId `com.example.recordwidget`。
- 抽出は再エンコードなし、出力は `.m4a`（MUXER_OUTPUT_MPEG_4）。AAC 以外は失敗を返す。
- 保存先 `filesDir/tracks/`、一覧 `filesDir/tracks.json`。
- UI 文言は日本語。
- この環境では Android SDK・Maven に到達できずビルド不可。ビルドとテストはユーザーの Android Studio（または CI）で行う。

## Review Focus
1. 音声トラックのない動画 → クラッシュせず「音声がありません」表示。
2. 曲が 0 件でウィジェットをタップ → アプリを開いて取り込みを促す。
3. 再生中に再生中の曲を削除 → プレイヤーのキューを再構築して継続。
4. Android 13+ の通知権限未許可 → 再生自体は動く（通知だけ出ない）。
5. 端末再起動後のウィジェット → 停止状態・先頭曲名で表示。

---

### Task 1: プロジェクト雛形 + Track/TrackStore
**Files:** Create `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/src/main/java/com/example/recordwidget/TrackStore.kt`; Test `app/src/test/java/com/example/recordwidget/TrackStoreTest.kt`
**Produces:** `data class Track(id: String, title: String, fileName: String)`; `class TrackStore(dir: File)` with `all(): List<Track>`, `add(t: Track)`, `rename(id, title)`, `remove(id): Track?`, `move(from: Int, to: Int)`。JSON は org.json ではなく手書き最小シリアライザ（JVM テストで動かすため）。
- [ ] テスト: add→all の順序保持、rename、remove がファイルも消す、move、タイトルに `"` と改行を含んでも往復できる、壊れた JSON で空リスト。
- [ ] 実装 → `./gradlew test` PASS → commit

### Task 2: AudioExtractor
**Files:** Create `AudioExtractor.kt`
**Produces:** `sealed class ExtractResult { Ok(file: File); NoAudio; Unsupported(mime: String); Failed(msg: String) }`; `fun extractAudio(ctx: Context, src: Uri, out: File): ExtractResult`。最初の `audio/` トラックを選び、`audio/mp4a-latm` 以外は Unsupported。失敗時は out を削除。
- [ ] 実機確認: 画面録画・カメラ動画・音声なし動画（Review Focus 1）。

### Task 3: PlaybackService
**Files:** Create `PlaybackService.kt`
**Consumes:** TrackStore。**Produces:** MediaSessionService。`ACTION_TOGGLE`, `ACTION_NEXT`, `ACTION_RELOAD` を `onStartCommand` で受ける。REPEAT_MODE_ALL。状態変化で `RecordWidget.update(ctx, playing: Boolean, title: String?)` を呼ぶ。0 件なら MainActivity を開く（Review Focus 2）。RELOAD は現在曲 id を保ってキュー再構築（Review Focus 3）。

### Task 4: RecordWidget
**Files:** Create `RecordWidget.kt`, `res/layout/widget_record.xml`, `res/drawable/record_disc.xml`, `res/drawable/tonearm.xml`, `res/xml/record_widget_info.xml`
**Produces:** `object RecordWidget { fun update(ctx, playing, title) }`。盤タップ=TOGGLE、次へボタン=NEXT（ForegroundService PendingIntent）。再生中は針画像を盤上に、停止中は上げる。onUpdate は停止表示（Review Focus 5）。

### Task 5: MainActivity + Manifest
**Files:** Create `MainActivity.kt`, `res/layout/activity_main.xml`, `res/layout/item_track.xml`, `AndroidManifest.xml`, `res/values/*`
- 取り込み: `PickVisualMedia(VideoOnly)` と `ACTION_SEND video/*` 受け取り。抽出はバックグラウンドスレッド、結果をトースト。
- 一覧: 名前変更（ダイアログ）、削除、上下移動ボタン。変更後 `ACTION_RELOAD`。
- 13+ で POST_NOTIFICATIONS を要求（拒否でも続行、Review Focus 4）。
- [ ] 実機で通し確認 → commit
