/* Copyright 2021 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.timeline.viewmodel

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.google.gson.Gson
import com.keylesspalace.tusky.components.timeline.Placeholder
import com.keylesspalace.tusky.components.timeline.toEntity
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.db.TimelineStatusEntity
import com.keylesspalace.tusky.db.TimelineStatusWithAccount
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.dec
import kotlinx.coroutines.rx3.await
import retrofit2.HttpException

@OptIn(ExperimentalPagingApi::class)
class CachedTimelineRemoteMediator(
    accountManager: AccountManager,
    private val api: MastodonApi,
    private val db: AppDatabase,
    private val gson: Gson
) : RemoteMediator<Int, TimelineStatusWithAccount>() {

    private var initialRefresh = false

    private val timelineDao = db.timelineDao()
    private val activeAccount = accountManager.activeAccount!!

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, TimelineStatusWithAccount>
    ): MediatorResult {

        try {
            var dbEmpty = false
            if (!initialRefresh && loadType == LoadType.REFRESH) {
                val topId = timelineDao.getTopId(activeAccount.id)
                topId?.let { cachedTopId ->
                    val statusResponse = api.homeTimeline(
                        maxId = cachedTopId,
                        limit = state.config.pageSize
                    ).await()

                    val statuses = statusResponse.body()
                    if (statusResponse.isSuccessful && statuses != null) {
                        db.withTransaction {
                            replaceStatusRange(statuses, state)
                        }
                    }
                }
                initialRefresh = true
                dbEmpty = topId == null
            }

            val statusResponse = when (loadType) {
                LoadType.REFRESH -> {
                    api.homeTimeline(limit = state.config.pageSize).await()
                }
                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    val maxId = state.pages.findLast { it.data.isNotEmpty() }?.data?.lastOrNull()?.status?.serverId
                    api.homeTimeline(maxId = maxId, limit = state.config.pageSize).await()
                }
            }

            val statuses = statusResponse.body()
            if (!statusResponse.isSuccessful || statuses == null) {
                return MediatorResult.Error(HttpException(statusResponse))
            }

            db.withTransaction {
                val overlappedStatuses = replaceStatusRange(statuses, state)

                if (loadType == LoadType.REFRESH && overlappedStatuses == 0 && statuses.isNotEmpty() && !dbEmpty) {
                    timelineDao.insertStatus(
                        Placeholder(statuses.last().id.dec(), loading = false).toEntity(activeAccount.id)
                    )
                }
            }
            return MediatorResult.Success(endOfPaginationReached = statuses.isEmpty())
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }

    /**
     * Deletes all statuses in a given range and inserts new statuses.
     * This is necessary so statuses that have been deleted on the server are cleaned up.
     * Should be run in a transaction as it executes multiple db updates
     * @param statuses the new statuses
     * @return the number of old statuses that have been cleared from the database
     */
    private suspend fun replaceStatusRange(statuses: List<Status>, state: PagingState<Int, TimelineStatusWithAccount>): Int {
        val overlappedStatuses = if (statuses.isNotEmpty()) {
            timelineDao.deleteRange(activeAccount.id, statuses.last().id, statuses.first().id)
        } else {
            0
        }

        for (status in statuses) {
            timelineDao.insertAccount(status.account.toEntity(activeAccount.id, gson))
            status.reblog?.account?.toEntity(activeAccount.id, gson)?.let { rebloggedAccount ->
                timelineDao.insertAccount(rebloggedAccount)
            }

            // check if we already have one of the newly loaded statuses cached locally
            // in case we do, copy the local state (expanded, contentShowing, contentCollapsed) over so it doesn't get lost
            var oldStatus: TimelineStatusEntity? = null
            for (page in state.pages) {
                oldStatus = page.data.find { s ->
                    s.status.serverId == status.id
                }?.status
                if (oldStatus != null) break
            }

            val expanded = oldStatus?.expanded ?: activeAccount.alwaysOpenSpoiler
            val contentShowing = oldStatus?.contentShowing ?: activeAccount.alwaysShowSensitiveMedia || !status.actionableStatus.sensitive
            val contentCollapsed = oldStatus?.contentCollapsed ?: true

            timelineDao.insertStatus(
                status.toEntity(
                    timelineUserId = activeAccount.id,
                    gson = gson,
                    expanded = expanded,
                    contentShowing = contentShowing,
                    contentCollapsed = contentCollapsed
                )
            )
        }
        return overlappedStatuses
    }
}
