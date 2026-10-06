package com.orchid.wagelivetracker.data.repository

/** Small profile boundary for presentation tests; production remains Room-backed. */
interface WorkProfileStore {
    suspend fun getCurrentProfile(): WorkProfile?
    suspend fun saveProfile(profile: WorkProfile, makeCurrent: Boolean = true): WorkProfile
}
