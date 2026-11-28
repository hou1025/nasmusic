package com.nasmusic.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        NasSourceEntity::class
    ],
    version = 1,
    // 导出 schema 供版本回溯/AutoMigration 校验；不再静默丢弃用户数据。
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun nasSourceDao(): NasSourceDao

    companion object {
        /**
         * 迁移骨架：当前 version=1 尚无结构变更，因此不注册任何迁移。
         * 后续任何实体字段变化都必须：
         *   1) 把 @Database(version = ...) 递增；
         *   2) 在此实现 MIGRATION_n_m（ALTER TABLE / CREATE TEMP TABLE + 数据搬运）；
         *   3) 在 build() 里 .addMigrations(MIGRATION_n_m)。
         * 严禁恢复 fallbackToDestructiveMigration()，那会静默清空收藏/歌单/播放记录/音源配置。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 预留：示例
                // db.execSQL("ALTER TABLE songs ADD COLUMN newColumn INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nasmusic.db")
                // 故意不调用 fallbackToDestructiveMigration()：老用户数据必须保留
                .build()
    }
}
