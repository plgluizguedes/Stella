package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.StellaDao
import com.example.data.model.Attachment
import com.example.data.model.ExamDocument
import com.example.data.model.FeedingRecord
import com.example.data.model.GlucoseRecord
import com.example.data.model.Medication
import com.example.data.model.MedicationAdministration
import com.example.data.model.Reminder
import com.example.data.model.StellaProfile
import com.example.data.model.VaccinePreventionRecord
import com.example.data.model.VeterinaryAppointment
import com.example.data.model.WeightRecord

@Database(
    entities = [
        StellaProfile::class,
        GlucoseRecord::class,
        Medication::class,
        MedicationAdministration::class,
        VeterinaryAppointment::class,
        ExamDocument::class,
        FeedingRecord::class,
        WeightRecord::class,
        VaccinePreventionRecord::class,
        Reminder::class,
        Attachment::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stellaDao(): StellaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 1 -> 2: tabela de anexos (vários arquivos por consulta ou exame/documento).
         * Só ADICIONA uma tabela e um índice; nenhum dado existente é tocado.
         * O SQL precisa bater exatamente com a entidade Attachment (o Room confere ao abrir).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ownerType` TEXT NOT NULL, " +
                        "`ownerId` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`localPath` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_ownerType_ownerId` " +
                        "ON `attachments` (`ownerType`, `ownerId`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stella_health_db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
