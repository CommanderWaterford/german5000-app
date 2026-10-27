package com.vocab5000.core.database

import kotlinx.coroutines.flow.Flow

class AssessmentRepository(private val db: UserDatabase) {
    private val dao = db.assessmentDao()

    suspend fun inProgress(): AssessmentEntity? = dao.inProgress()
    suspend fun save(entity: AssessmentEntity) = dao.upsert(entity)
    fun observeLatestCompleted(): Flow<AssessmentEntity?> = dao.observeLatestCompleted()
    suspend fun latestCompleted(): AssessmentEntity? = dao.latestCompleted()
}
