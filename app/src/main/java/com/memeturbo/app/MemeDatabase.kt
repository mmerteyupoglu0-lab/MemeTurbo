package com.memeturbo.app

import android.content.Context
import androidx.room.*

@Entity(tableName = "meme_videos")
data class MemeVideo(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val durationMs: Long,
    val spokenText: String,
    val screenText: String,
    val visualLabels: String,
    val isVideo: Boolean,
    val lastModified: Long
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

    @Query("SELECT filePath FROM meme_videos")
    suspend fun getAllIndexedPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeme(meme: MemeVideo)

    @Query("UPDATE meme_videos SET visualLabels = visualLabels || ' ' || :newTags WHERE filePath = :path")
    suspend fun appendCustomTags(path: String, newTags: String)

    @Query("UPDATE meme_videos SET spokenText = :spokenText WHERE filePath = :path")
    suspend fun updateSpokenText(path: String, spokenText: String)

    // Eski taramalardan kalan tüm "skin, mouth, hair, muscle" İngilizce çöplerini tek saniyede siler
    @Query("UPDATE meme_videos SET visualLabels = '' WHERE visualLabels LIKE '%skin%' OR visualLabels LIKE '%mouth%' OR visualLabels LIKE '%hair%' OR visualLabels LIKE '%muscle%'")
    suspend fun clearJunkVisualLabels()

    // Sadece henüz repliği (sesi) taranmamış videoları getirir (Hızlı onarım için)
    @Query("SELECT * FROM meme_videos WHERE isVideo = 1 AND (spokenText IS NULL OR spokenText = '')")
    suspend fun getVideosMissingVoice(): List<MemeVideo>

    @Query("SELECT COUNT(*) FROM meme_videos")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM meme_videos WHERE isVideo = 1")
    suspend fun getVideoCount(): Int

    @Query("SELECT COUNT(*) FROM meme_videos WHERE isVideo = 0")
    suspend fun getImageCount(): Int
}

@Database(entities = [MemeVideo::class], version = 2, exportSchema = false)
abstract class MemeDatabase : RoomDatabase() {
    abstract fun memeDao(): MemeDao

    companion object {
        @Volatile
        private var INSTANCE: MemeDatabase? = null

        fun getInstance(context: Context): MemeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemeDatabase::class.java,
                    "memeturbo_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
