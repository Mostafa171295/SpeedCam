package com.example.speedcam

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records")
data class SpeedRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val vehicleType: String,   // اسم VehicleType
    val speedKmh: Double,
    val direction: Int,        // 1 أو 2
    @ColumnInfo(defaultValue = "''") val plateLetters: String = "",   // حروف اللوحة (لو اتقرأت)
    @ColumnInfo(defaultValue = "''") val plateDigits: String = "",    // أرقام اللوحة بأرقام إنجليزية
    @ColumnInfo(defaultValue = "0") val plateConf: Int = 0,           // ثقة قراءة اللوحة 0..100
    @ColumnInfo(defaultValue = "0") val speedConf: Int = 0,           // ثقة قراءة السرعة 1..100 (0 = غير معروفة)
    @ColumnInfo(defaultValue = "0") val speedErr: Float = 0f,         // هامش الخطأ ± كم/س (0 = غير معروف)
    @ColumnInfo(defaultValue = "''") val evidencePath: String = "",     // مسار لقطة الإثبات (فاضي = مفيش لقطة)
    @ColumnInfo(defaultValue = "0") val lane: Int = 0                  // رقم الحارة (0 = غير معروف؛ بيتحدد مع تحويل المنظور)
)

/** حالة غير عادية: عكس اتجاه، مركبة واقفة، ازدحام، سرعة عالية جداً. */
@Entity(tableName = "anomalies")
data class AnomalyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val details: String
)

/** ترقية من النسخة 6 إلى 7: عمود الحارة + جدول الحالات غير العادية، بدون مسح القياسات القديمة. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN lane INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS anomalies (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, kind TEXT NOT NULL, details TEXT NOT NULL)")
    }
}

/** ترقية من النسخة 2 إلى 3: إضافة أعمدة اللوحة فقط، بدون مسح القياسات القديمة. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN plateLetters TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE records ADD COLUMN plateDigits TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE records ADD COLUMN plateConf INTEGER NOT NULL DEFAULT 0")
    }
}

/** ترقية من النسخة 3 إلى 4: إضافة عمود ثقة السرعة فقط، بدون مسح القياسات القديمة. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN speedConf INTEGER NOT NULL DEFAULT 0")
    }
}

/** ترقية من النسخة 4 إلى 5: إضافة عمود هامش الخطأ فقط. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN speedErr REAL NOT NULL DEFAULT 0")
    }
}

/** ترقية من النسخة 5 إلى 6: إضافة عمود مسار لقطة الإثبات فقط. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE records ADD COLUMN evidencePath TEXT NOT NULL DEFAULT ''")
    }
}

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(r: SpeedRecord): Long

    @Query("SELECT * FROM records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SpeedRecord>>

    @Query("SELECT * FROM records ORDER BY timestamp DESC")
    suspend fun getAll(): List<SpeedRecord>

    @Delete
    suspend fun delete(r: SpeedRecord)

    @Query("DELETE FROM records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM records")
    suspend fun clear()

    @Insert
    suspend fun insertAnomaly(a: AnomalyRecord): Long

    @Query("SELECT * FROM anomalies ORDER BY timestamp DESC LIMIT 200")
    fun observeAnomalies(): Flow<List<AnomalyRecord>>

    @Query("DELETE FROM anomalies")
    suspend fun clearAnomalies()

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int

    /** أقدم n سجل (للأرشفة قبل المسح التلقائي). */
    @Query("SELECT * FROM records ORDER BY timestamp ASC LIMIT :n")
    suspend fun oldest(n: Int): List<SpeedRecord>

    @Query("DELETE FROM records WHERE id IN (SELECT id FROM records ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int
}

@Database(entities = [SpeedRecord::class, AnomalyRecord::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "speedcam.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
