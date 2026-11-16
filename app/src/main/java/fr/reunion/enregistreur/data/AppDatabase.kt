package fr.reunion.enregistreur.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Meeting::class, Speaker::class, Segment::class, ActionItem::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meetings(): MeetingDao
    abstract fun speakers(): SpeakerDao
    abstract fun segments(): SegmentDao
    abstract fun actions(): ActionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "reunions.db").build()
    }
}
