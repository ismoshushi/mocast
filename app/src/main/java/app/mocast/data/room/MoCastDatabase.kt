package app.mocast.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Room 数据库（0.4.0 数据层）。
 *
 * - 数据库名 "mocast.db"，version=1，包 app.mocast.data.room。
 * - exportSchema=false：v1 不导出 schema JSON；若 coordinator 决定留档，
 *   改为 true 并在 app/build.gradle.kts 加
 *   `javaCompileOptions { annotationProcessorOptions { arguments += mapOf("room.schemaLocation" to "$projectDir/schemas") } }`
 *   （KSP 下用 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`）。
 * - 不设 fallbackToDestructiveMigration()：v1 之后任何升版都必须补 Migration，
 *   绝不允许静默删库。
 * - v2（0.4.0 补项）：episodes 表加 played / playedAt 两列（默认未听），
 *   新增 queue_items 表。见底部的 MIGRATION_1_2。
 * - 单例 get(context)：首次创建后在 IO 线程跑一次 LegacyMigration
 *   （进程内只尝试一次；迁移本身幂等：已写 flag 或 Room 非空时直接返回）。
 *   迁移是同步等待的——RoomStore 构造即完成迁移，调用方读到的已是迁移后数据，
 *   不用在别处再调一次 migrateIfNeeded。代价是升级后首次启动在 get() 处
 *   多一次 IO（旧数据量小时可忽略；大数据量见 LegacyMigration 的分批说明）。
 */
@Database(
    entities = [
        ShowEntity::class,
        EpisodeEntity::class,
        ProgressEntity::class,
        DownloadEntity::class,
        AsrTranscriptEntity::class,
        QueueItemEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MoCastDatabase : RoomDatabase() {

    abstract fun showDao(): ShowDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun progressDao(): ProgressDao
    abstract fun downloadDao(): DownloadDao
    abstract fun asrTranscriptDao(): AsrTranscriptDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val DATABASE_NAME = "mocast.db"

        @Volatile
        private var INSTANCE: MoCastDatabase? = null

        /** 进程内只尝试一次迁移（迁移本身也靠 prefs flag + Room 为空双重幂等）。 */
        @Volatile
        private var migrationAttempted = false

        fun get(context: Context): MoCastDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): MoCastDatabase {
            val db = Room.databaseBuilder(
                context,
                MoCastDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2).build()
            if (!migrationAttempted) {
                migrationAttempted = true
                // 同步等迁移完成再返回：保证首个 RoomStore 读到的是迁移后数据。
                // 迁移内部永不抛异常（坏数据按条跳过），这里不会卡死启动。
                runBlocking(Dispatchers.IO) {
                    LegacyMigration.migrateIfNeeded(context, db)
                }
            }
            return db
        }

        /** 仅供测试：替换单例（插 in-memory 库）。生产代码不要调。 */
        fun setInstanceForTest(db: MoCastDatabase?) {
            synchronized(this) {
                INSTANCE = db
                migrationAttempted = true
            }
        }
    }
}

/**
 * v1 → v2（0.4.0 补项）：只加列、只建新表，不改旧列、不删数据。
 * - episodes.played INTEGER NOT NULL DEFAULT 0：v1 旧库的全部单集默认未听。
 * - episodes.playedAt INTEGER NOT NULL DEFAULT 0：未听为 0。
 * - queue_items 新表：主键 episodeId，position 索引。
 * 任何一条语句失败时 Room 会回滚整个迁移（迁移在事务里跑），不会留半吊子库。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE episodes ADD COLUMN played INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE episodes ADD COLUMN playedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `queue_items` (" +
                "`episodeId` TEXT NOT NULL, `showId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`episodeId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_position` ON `queue_items` (`position`)")
    }
}
