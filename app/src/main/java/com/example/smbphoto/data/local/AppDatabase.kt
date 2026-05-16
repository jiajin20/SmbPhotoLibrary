package com.example.smbphoto.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库 - SmbPhotoLibrary 图片索引存储
 *
 * 单例模式，确保整个应用只会有一个数据库实例
 */
@Database(
    entities = [ImageIndex::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageIndexDao(): ImageIndexDao

    companion object {
        private const val DATABASE_NAME = "smb_photo_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例实例
         *
         * @param context Application Context（会自动转换为 Application Context）
         * @return AppDatabase 实例
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // 允许在主线程查询（实际上协程会切到 IO 线程）
                    .allowMainThreadQueries()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
