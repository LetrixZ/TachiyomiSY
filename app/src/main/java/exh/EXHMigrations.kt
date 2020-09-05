package exh

import android.content.Context
import com.elvishew.xlog.XLog
import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.pushtorefresh.storio.sqlite.queries.Query
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.models.DHistory
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.resolvers.MangaUrlPutResolver
import eu.kanade.tachiyomi.data.database.tables.ChapterTable
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.updater.UpdaterJob
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.Hitomi
import eu.kanade.tachiyomi.source.online.all.NHentai
import exh.merged.sql.models.MergedMangaReference
import exh.source.BlacklistedSources
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import uy.kohesive.injekt.injectLazy

object EXHMigrations {
    private val db: DatabaseHelper by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val gson: Gson by injectLazy()

    private val logger = XLog.tag("EXHMigrations")

    /**
     * Performs a migration when the application is updated.
     *
     * @param preferences Preferences of the application.
     * @return true if a migration is performed, false otherwise.
     */
    fun upgrade(preferences: PreferencesHelper): Boolean {
        val context = preferences.context
        val oldVersion = preferences.eh_lastVersionCode().get()
        try {
            if (oldVersion < BuildConfig.VERSION_CODE) {
                // Fresh install
                if (oldVersion == 0) {
                    // Set up default background tasks
                    UpdaterJob.setupTask(context)
                    ExtensionUpdateJob.setupTask(context)
                    LibraryUpdateJob.setupTask(context)
                    return false
                }
                if (oldVersion < 4) {
                    db.inTransaction {
                        db.lowLevel().executeSQL(
                            RawQuery.builder()
                                .query(
                                    """
                                    UPDATE ${MangaTable.TABLE}
                                        SET ${MangaTable.COL_SOURCE} = $HBROWSE_SOURCE_ID
                                        WHERE ${MangaTable.COL_SOURCE} = 6912
                                    """.trimIndent()
                                )
                                .affectsTables(MangaTable.TABLE)
                                .build()
                        )
                        // Migrate BHrowse URLs
                        val hBrowseManga = db.db.get()
                            .listOfObjects(Manga::class.java)
                            .withQuery(
                                Query.builder()
                                    .table(MangaTable.TABLE)
                                    .where("${MangaTable.COL_SOURCE} = $HBROWSE_SOURCE_ID")
                                    .build()
                            )
                            .prepare()
                            .executeAsBlocking()
                        hBrowseManga.forEach {
                            it.url = it.url + "/c00001/"
                        }

                        db.db.put()
                            .objects(hBrowseManga)
                            // Extremely slow without the resolver :/
                            .withPutResolver(MangaUrlPutResolver())
                            .prepare()
                            .executeAsBlocking()
                    }
                }
                if (oldVersion < 5) {
                    db.inTransaction {
                        // Migrate Tsumino source IDs
                        db.lowLevel().executeSQL(
                            RawQuery.builder()
                                .query(
                                    """
                                    UPDATE ${MangaTable.TABLE}
                                        SET ${MangaTable.COL_SOURCE} = ${Hitomi.otherId}
                                        WHERE ${MangaTable.COL_SOURCE} = 6910
                                    """.trimIndent()
                                )
                                .affectsTables(MangaTable.TABLE)
                                .build()
                        )
                    }
                }
                if (oldVersion < 6) {
                    db.inTransaction {
                        db.lowLevel().executeSQL(
                            RawQuery.builder()
                                .query(
                                    """
                                    UPDATE ${MangaTable.TABLE}
                                        SET ${MangaTable.COL_SOURCE} = $PERV_EDEN_EN_SOURCE_ID
                                        WHERE ${MangaTable.COL_SOURCE} = 6905
                                    """.trimIndent()
                                )
                                .affectsTables(MangaTable.TABLE)
                                .build()
                        )
                        db.lowLevel().executeSQL(
                            RawQuery.builder()
                                .query(
                                    """
                                    UPDATE ${MangaTable.TABLE}
                                        SET ${MangaTable.COL_SOURCE} = $PERV_EDEN_IT_SOURCE_ID
                                        WHERE ${MangaTable.COL_SOURCE} = 6906
                                    """.trimIndent()
                                )
                                .affectsTables(MangaTable.TABLE)
                                .build()
                        )
                        db.lowLevel().executeSQL(
                            RawQuery.builder()
                                .query(
                                    """
                                    UPDATE ${MangaTable.TABLE}
                                        SET ${MangaTable.COL_SOURCE} = ${NHentai.otherId}
                                        WHERE ${MangaTable.COL_SOURCE} = 6907
                                    """.trimIndent()
                                )
                                .affectsTables(MangaTable.TABLE)
                                .build()
                        )
                    }
                }
                if (oldVersion < 7) {
                    db.inTransaction {
                        val mergedMangas = db.db.get()
                            .listOfObjects(Manga::class.java)
                            .withQuery(
                                Query.builder()
                                    .table(MangaTable.TABLE)
                                    .where("${MangaTable.COL_SOURCE} = $MERGED_SOURCE_ID")
                                    .build()
                            )
                            .prepare()
                            .executeAsBlocking()

                        if (mergedMangas.isNotEmpty()) {
                            val mangaConfigs = mergedMangas.mapNotNull { mergedManga -> readMangaConfig(mergedManga, gson)?.let { mergedManga to it } }
                            if (mangaConfigs.isNotEmpty()) {
                                val mangaToUpdate = mutableListOf<Manga>()
                                val mergedMangaReferences = mutableListOf<MergedMangaReference>()
                                mangaConfigs.onEach { mergedManga ->
                                    mergedManga.second.children.firstOrNull()?.url?.let {
                                        if (db.getManga(it, MERGED_SOURCE_ID).executeAsBlocking() != null) return@onEach
                                        mergedManga.first.url = it
                                    }
                                    mangaToUpdate += mergedManga.first
                                    mergedMangaReferences += MergedMangaReference(
                                        id = null,
                                        isInfoManga = false,
                                        getChapterUpdates = false,
                                        chapterSortMode = 0,
                                        chapterPriority = 0,
                                        downloadChapters = false,
                                        mergeId = mergedManga.first.id!!,
                                        mergeUrl = mergedManga.first.url,
                                        mangaId = mergedManga.first.id!!,
                                        mangaUrl = mergedManga.first.url,
                                        mangaSourceId = MERGED_SOURCE_ID
                                    )
                                    mergedManga.second.children.distinct().forEachIndexed { index, mangaSource ->
                                        val load = mangaSource.load(db, sourceManager) ?: return@forEachIndexed
                                        mergedMangaReferences += MergedMangaReference(
                                            id = null,
                                            isInfoManga = index == 0,
                                            getChapterUpdates = true,
                                            chapterSortMode = 0,
                                            chapterPriority = 0,
                                            downloadChapters = true,
                                            mergeId = mergedManga.first.id!!,
                                            mergeUrl = mergedManga.first.url,
                                            mangaId = load.manga.id!!,
                                            mangaUrl = load.manga.url,
                                            mangaSourceId = load.source.id
                                        )
                                    }
                                }
                                db.db.put()
                                    .objects(mangaToUpdate)
                                    // Extremely slow without the resolver :/
                                    .withPutResolver(MangaUrlPutResolver())
                                    .prepare()
                                    .executeAsBlocking()
                                db.insertMergedMangas(mergedMangaReferences).executeAsBlocking()

                                val loadedMangaList = mangaConfigs.map { it.second.children }.flatten().mapNotNull { it.load(db, sourceManager) }.distinct()
                                val chapters = db.db.get()
                                    .listOfObjects(Chapter::class.java)
                                    .withQuery(
                                        Query.builder()
                                            .table(ChapterTable.TABLE)
                                            .where("${ChapterTable.COL_MANGA_ID} IN (${mergedMangas.filter { it.id != null }.joinToString { it.id.toString() }})")
                                            .build()
                                    )
                                    .prepare()
                                    .executeAsBlocking()
                                val mergedMangaChapters = db.db.get()
                                    .listOfObjects(Chapter::class.java)
                                    .withQuery(
                                        Query.builder()
                                            .table(ChapterTable.TABLE)
                                            .where("${ChapterTable.COL_MANGA_ID} IN (${loadedMangaList.filter { it.manga.id != null }.joinToString { it.manga.id.toString() }})")
                                            .build()
                                    )
                                    .prepare()
                                    .executeAsBlocking()
                                val mergedMangaChaptersMatched = mergedMangaChapters.mapNotNull { chapter -> loadedMangaList.firstOrNull { it.manga.id == chapter.id }?.let { it to chapter } }
                                val parsedChapters = chapters.filter { it.read || it.last_page_read != 0 }.mapNotNull { chapter -> readUrlConfig(chapter.url, gson)?.let { chapter to it } }
                                val chaptersToUpdate = mutableListOf<Chapter>()
                                parsedChapters.forEach { parsedChapter ->
                                    mergedMangaChaptersMatched.firstOrNull { it.second.url == parsedChapter.second.url && it.first.source.id == parsedChapter.second.source && it.first.manga.url == parsedChapter.second.mangaUrl }?.let {
                                        chaptersToUpdate += it.second.apply {
                                            read = parsedChapter.first.read
                                            last_page_read = parsedChapter.first.last_page_read
                                        }
                                    }
                                }
                                db.deleteChapters(mergedMangaChapters).executeAsBlocking()
                                db.updateChaptersProgress(chaptersToUpdate).executeAsBlocking()
                            }
                        }
                    }
                }

                // if (oldVersion < 1) { } (1 is current release version)
                // do stuff here when releasing changed crap

                // TODO BE CAREFUL TO NOT FUCK UP MergedSources IF CHANGING URLs

                preferences.eh_lastVersionCode().set(BuildConfig.VERSION_CODE)

                return true
            }
        } catch (e: Exception) {
            logger.e("Failed to migrate app from $oldVersion -> ${BuildConfig.VERSION_CODE}!", e)
        }
        return false
    }

    fun migrateBackupEntry(manga: MangaImpl): MangaImpl {
        if (manga.source == 6905L) {
            manga.source = PERV_EDEN_EN_SOURCE_ID
        }

        if (manga.source == 6906L) {
            manga.source = PERV_EDEN_IT_SOURCE_ID
        }

        if (manga.source == 6907L) {
            // Migrate the old source to the delegated one
            manga.source = NHentai.otherId
            // Migrate nhentai URLs
            manga.url = getUrlWithoutDomain(manga.url)
        }

        // Migrate HentaiCafe source IDs
        if (manga.source == 6908L) {
            manga.source = HENTAI_CAFE_SOURCE_ID
        }

        // Migrate Tsumino source IDs
        if (manga.source == 6909L) {
            manga.source = TSUMINO_SOURCE_ID
        }

        if (manga.source == 6910L) {
            manga.source = Hitomi.otherId
        }

        if (manga.source == 6912L) {
            manga.source = HBROWSE_SOURCE_ID
        }

        // Allow importing of EHentai extension backups
        if (manga.source in BlacklistedSources.EHENTAI_EXT_SOURCES) {
            manga.source = EH_SOURCE_ID
        }

        return manga
    }

    private fun backupDatabase(context: Context, oldMigrationVersion: Int) {
        val backupLocation = File(File(context.filesDir, "exh_db_bck"), "$oldMigrationVersion.bck.db")
        if (backupLocation.exists()) return // Do not backup same version twice

        val dbLocation = context.getDatabasePath(db.lowLevel().sqliteOpenHelper().databaseName)
        try {
            dbLocation.copyTo(backupLocation, overwrite = true)
        } catch (t: Throwable) {
            XLog.w("Failed to backup database!")
        }
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig)
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }

    private data class UrlConfig(
        @SerializedName("s")
        val source: Long,
        @SerializedName("u")
        val url: String,
        @SerializedName("m")
        val mangaUrl: String
    )

    private data class MangaConfig(
        @SerializedName("c")
        val children: List<MangaSource>
    ) {
        companion object {
            fun readFromUrl(gson: Gson, url: String): MangaConfig? {
                return try {
                    gson.fromJson(url)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun readMangaConfig(manga: SManga, gson: Gson): MangaConfig? {
        return MangaConfig.readFromUrl(gson, manga.url)
    }

    private data class MangaSource(
        @SerializedName("s")
        val source: Long,
        @SerializedName("u")
        val url: String
    ) {
        fun load(db: DatabaseHelper, sourceManager: SourceManager): LoadedMangaSource? {
            val manga = db.getManga(url, source).executeAsBlocking() ?: return null
            val source = sourceManager.getOrStub(source)
            return LoadedMangaSource(source, manga)
        }
    }

    private fun readUrlConfig(url: String, gson: Gson): UrlConfig? {
        return try {
            gson.fromJson(url)
        } catch (e: Exception) {
            null
        }
    }

    private data class LoadedMangaSource(val source: Source, val manga: Manga)
}

data class BackupEntry(
    val manga: Manga,
    val chapters: List<Chapter>,
    val categories: List<String>,
    val history: List<DHistory>,
    val tracks: List<Track>
)
