package io.github.vstory.hook.notifyfilter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEntity::class, RuleEntity::class, WhitelistEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun whitelistDao(): WhitelistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "notification_cleaner.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `whitelist` (" +
                        "`packageName` TEXT NOT NULL PRIMARY KEY, " +
                        "`appName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `rules` ADD COLUMN `conditions` TEXT")
            }
        }

    /** 1.1.11：通知表新增 learnCount（重复学习次数），旧数据默认 0 */
    private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `learnCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 1.2.0（ImprovePlan P0-1）：通知表加索引，消除决策热路径全表扫描 */
    private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_key` ON `notifications` (`key`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notifications_packageName_title_content_postTime` " +
                    "ON `notifications` (`packageName`, `title`, `content`, `postTime`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_expireAt` ON `notifications` (`expireAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_learned` ON `notifications` (`learned`)")
        }
    }

    /**
     * 1.3.2（P2-2）：去重改 64 位 contentHash——索引体积骤降、比较 O(1)。
     * 存量行全部填哨兵 0：去重只在 60s 窗口内生效，老行永远不可能参与匹配；
     * 近 60s 的少量行接受一次性不去重（1.3.2Plan P2-2 修订4，无感）。
     */
    private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `notifications` SET `contentHash` = 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notifications_packageName_contentHash_postTime` " +
                    "ON `notifications` (`packageName`, `contentHash`, `postTime`)",
            )
            db.execSQL("DROP INDEX IF EXISTS `index_notifications_packageName_title_content_postTime`")
        }
    }

    /**
     * 三态改造：通知表加 seq / dismissTime / dismissReason。
     * 存量行按「已取消」回填（dismissTime = postTime）——升级瞬间无从得知通知栏现存哪些，
     * 让它们全落到「正显示」是假的；下次监听连接时由 reconcileVisible 按实际通知栏校正。
     */
    private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `dismissTime` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `dismissReason` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `notifications` SET `dismissTime` = `postTime`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_key_seq` ON `notifications` (`key`, `seq`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notifications_seq_dismissTime` " +
                    "ON `notifications` (`seq`, `dismissTime`)",
            )
            db.execSQL("DROP INDEX IF EXISTS `index_notifications_key`")
        }
    }
    }
}
