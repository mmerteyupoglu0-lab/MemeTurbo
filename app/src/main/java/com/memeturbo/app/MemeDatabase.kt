package com.memeturbo.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "meme_videos")
data class MemeVideo(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val durationMs: Long,
    val spokenText: String,
    val screenText: String,
    val visualLabels: String,
    val isVideo: Boolean,
    val lastModified: Long,
    val albumName: String = ""
)

@Dao
interface MemeDao {
    @Query("""
        SELECT * FROM meme_videos 
        WHERE (spokenText LIKE '%' || :query || '%' 
           OR screenText LIKE '%' || :query || '%' 
           OR visualLabels LIKE '%' || :query || '%' 
           OR fileName LIKE '%' || :query || '%')
        ORDER BY lastModified DESC
    """)
    suspend fun searchMemes(query: String): List<MemeVideo>

    @Query("SELECT * FROM meme_videos ORDER BY lastModified DESC")
    suspend fun getRecentMemes(): List<MemeVideo>

    @Query("SELECT * FROM meme_videos ORDER BY lastModified DESC")
    suspend fun getAllMemes(): List<MemeVideo>

    @Query("SELECT * FROM meme_videos WHERE isVideo = 1 ORDER BY lastModified DESC")
    suspend fun getVideoMemes(): List<MemeVideo>

    @Query("SELECT * FROM meme_videos WHERE isVideo = 0 ORDER BY lastModified DESC")
    suspend fun getImageMemes(): List<MemeVideo>

    @Query("SELECT DISTINCT albumName FROM meme_videos WHERE albumName != '' ORDER BY albumName ASC")
    suspend fun getIndexedAlbumNames(): List<String>

    @Query("SELECT filePath FROM meme_videos")
    suspend fun getAllIndexedPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeme(meme: MemeVideo)

    @Update
    suspend fun updateMeme(meme: MemeVideo)

    @Query("UPDATE meme_videos SET visualLabels = CASE WHEN visualLabels = '' THEN :newTags ELSE visualLabels || ' • ' || :newTags END WHERE filePath = :path")
    suspend fun appendCustomTags(path: String, newTags: String)

    @Query("UPDATE meme_videos SET spokenText = :spokenText WHERE filePath = :path")
    suspend fun updateSpokenText(path: String, spokenText: String)

    @Query("UPDATE meme_videos SET spokenText = :spokenText, screenText = :screenText, visualLabels = :visualLabels WHERE filePath = :path")
    suspend fun updateFields(path: String, spokenText: String, screenText: String, visualLabels: String)

    @Query("UPDATE meme_videos SET albumName = :albumName WHERE filePath = :path")
    suspend fun updateAlbumName(path: String, albumName: String)

    @Query("DELETE FROM meme_videos WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("SELECT * FROM meme_videos WHERE isVideo = 1 AND (spokenText IS NULL OR spokenText = '')")
    suspend fun getVideosMissingVoice(): List<MemeVideo>

    @Query("SELECT COUNT(*) FROM meme_videos")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM meme_videos WHERE isVideo = 1")
    suspend fun getVideoCount(): Int

    @Query("SELECT COUNT(*) FROM meme_videos WHERE isVideo = 0")
    suspend fun getImageCount(): Int
}

@Database(entities = [MemeVideo::class], version = 3, exportSchema = false)
abstract class MemeDatabase : RoomDatabase() {
    abstract fun memeDao(): MemeDao

    companion object {
        @Volatile
        private var INSTANCE: MemeDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE meme_videos ADD COLUMN albumName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): MemeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemeDatabase::class.java,
                    "memeturbo_db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
