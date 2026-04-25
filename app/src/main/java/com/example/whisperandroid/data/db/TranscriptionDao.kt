package com.example.whisperandroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TranscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranscriptionEntity): Long

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcriptions")
    suspend fun clear()
}
