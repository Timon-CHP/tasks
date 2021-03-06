package org.tasks.backup

import android.app.ProgressDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.todoroo.astrid.dao.TaskDaoBlocking
import com.todoroo.astrid.service.TaskMover
import com.todoroo.astrid.service.Upgrader
import com.todoroo.astrid.service.Upgrader.Companion.getAndroidColor
import org.tasks.LocalBroadcastManager
import org.tasks.R
import org.tasks.data.*
import org.tasks.data.Place.Companion.newPlace
import org.tasks.preferences.Preferences
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject

class TasksJsonImporter @Inject constructor(
        private val tagDataDao: TagDataDaoBlocking,
        private val userActivityDao: UserActivityDaoBlocking,
        private val taskDao: TaskDaoBlocking,
        private val locationDao: LocationDaoBlocking,
        private val localBroadcastManager: LocalBroadcastManager,
        private val alarmDao: AlarmDaoBlocking,
        private val tagDao: TagDaoBlocking,
        private val googleTaskDao: GoogleTaskDaoBlocking,
        private val googleTaskListDao: GoogleTaskListDaoBlocking,
        private val filterDao: FilterDaoBlocking,
        private val taskAttachmentDao: TaskAttachmentDaoBlocking,
        private val caldavDao: CaldavDaoBlocking,
        private val preferences: Preferences,
        private val taskMover: TaskMover,
        private val taskListMetadataDao: TaskListMetadataDaoBlocking) {

    private val result = ImportResult()

    private fun setProgressMessage(
            handler: Handler, progressDialog: ProgressDialog?, message: String) {
        if (progressDialog == null) {
            return
        }
        handler.post { progressDialog.setMessage(message) }
    }

    fun importTasks(context: Context, backupFile: Uri?, progressDialog: ProgressDialog?): ImportResult {
        val handler = Handler(context.mainLooper)
        val gson = Gson()
        val `is`: InputStream?
        `is` = try {
            context.contentResolver.openInputStream(backupFile!!)
        } catch (e: FileNotFoundException) {
            throw IllegalStateException(e)
        }
        val reader = InputStreamReader(`is`, TasksJsonExporter.UTF_8)
        val input = gson.fromJson(reader, JsonObject::class.java)
        try {
            val data = input["data"]
            val version = input["version"].asInt
            val backupContainer = gson.fromJson(data, BackupContainer::class.java)
            backupContainer.tags?.forEach { tagData ->
                tagData.setColor(themeToColor(context, version, tagData.getColor()!!))
                if (tagDataDao.getByUuid(tagData.remoteId!!) == null) {
                    tagDataDao.createNew(tagData)
                }
            }
            backupContainer.googleTaskAccounts?.forEach { googleTaskAccount ->
                if (googleTaskListDao.getAccount(googleTaskAccount.account!!) == null) {
                    googleTaskListDao.insert(googleTaskAccount)
                }
            }
            backupContainer.places?.forEach { place ->
                if (locationDao.getByUid(place.uid!!) == null) {
                    locationDao.insert(place)
                }
            }
            backupContainer.googleTaskLists?.forEach { googleTaskList ->
                googleTaskList.setColor(themeToColor(context, version, googleTaskList.getColor()!!))
                if (googleTaskListDao.getByRemoteId(googleTaskList.remoteId!!) == null) {
                    googleTaskListDao.insert(googleTaskList)
                }
            }
            backupContainer.filters?.forEach { filter ->
                filter.setColor(themeToColor(context, version, filter.getColor()!!))
                if (filterDao.getByName(filter.title!!) == null) {
                    filterDao.insert(filter)
                }
            }
            backupContainer.caldavAccounts?.forEach { account ->
                if (caldavDao.getAccountByUuid(account.uuid!!) == null) {
                    caldavDao.insert(account)
                }
            }
            backupContainer.caldavCalendars?.forEach { calendar ->
                calendar.color = themeToColor(context, version, calendar.color)
                if (caldavDao.getCalendarByUuid(calendar.uuid!!) == null) {
                    caldavDao.insert(calendar)
                }
            }
            backupContainer.taskListMetadata?.forEach { tlm ->
                val id = tlm.filter.takeIf { it?.isNotBlank() == true } ?: tlm.tagUuid!!
                if (taskListMetadataDao.fetchByTagOrFilter(id) == null) {
                    taskListMetadataDao.insert(tlm)
                }
            }
            backupContainer.tasks?.forEach { backup ->
                result.taskCount++
                setProgressMessage(
                        handler,
                        progressDialog,
                        context.getString(R.string.import_progress_read, result.taskCount))
                val task = backup.task
                if (taskDao.fetch(task.uuid) != null) {
                    result.skipCount++
                    return@forEach
                }
                task.suppressRefresh()
                task.suppressSync()
                taskDao.createNew(task)
                val taskId = task.id
                val taskUuid = task.uuid
                for (alarm in backup.alarms) {
                    alarm.task = taskId
                    alarmDao.insert(alarm)
                }
                for (comment in backup.comments) {
                    comment.targetId = taskUuid
                    if (version < 546) {
                        comment.convertPictureUri()
                    }
                    userActivityDao.createNew(comment)
                }
                for (googleTask in backup.google) {
                    googleTask.task = taskId
                    googleTaskDao.insert(googleTask)
                }
                for (location in backup.locations) {
                    val place = newPlace()
                    place.longitude = location.longitude
                    place.latitude = location.latitude
                    place.name = location.name
                    place.address = location.address
                    place.url = location.url
                    place.phone = location.phone
                    locationDao.insert(place)
                    val geofence = Geofence()
                    geofence.task = taskId
                    geofence.place = place.uid
                    geofence.radius = location.radius
                    geofence.isArrival = location.arrival
                    geofence.isDeparture = location.departure
                    locationDao.insert(geofence)
                }
                for (tag in backup.tags) {
                    tag.task = taskId
                    tag.setTaskUid(taskUuid)
                    tagDao.insert(tag)
                }
                backup.geofences?.forEach { geofence ->
                    geofence.task = taskId
                    locationDao.insert(geofence)
                }
                backup.attachments?.forEach { attachment ->
                    attachment.taskId = taskUuid
                    if (version < 546) {
                        attachment.convertPathUri()
                    }
                    taskAttachmentDao.insert(attachment)
                }
                backup.caldavTasks?.forEach { caldavTask ->
                    caldavTask.task = taskId
                    caldavDao.insert(caldavTask)
                }
                result.importCount++
            }
            googleTaskDao.updateParents()
            caldavDao.updateParents()
            backupContainer.intPrefs?.forEach { (key, value) ->
                if (Preferences.P_CURRENT_VERSION == key) {
                    return@forEach
                }
                preferences.setInt(key, value)
            }
            backupContainer.longPrefs?.forEach { (key, value) ->
                preferences.setLong(key, value)
            }
            backupContainer.stringPrefs?.forEach { (key, value) ->
                preferences.setString(key, value)
            }
            backupContainer.boolPrefs?.forEach { (key, value) ->
                preferences.setBoolean(key, value)
            }
            if (version < Upgrader.V8_2) {
                val themeIndex = preferences.getInt(R.string.p_theme_color, 7)
                preferences.setInt(
                        R.string.p_theme_color,
                        getAndroidColor(context, themeIndex))
            }
            if (version < Upgrader.V9_6) {
                taskMover.migrateLocalTasks()
            }
            reader.close()
            `is`!!.close()
        } catch (e: IOException) {
            Timber.e(e)
        }
        localBroadcastManager.broadcastRefresh()
        return result
    }

    private fun themeToColor(context: Context, version: Int, color: Int) =
            if (version < Upgrader.V8_2) getAndroidColor(context, color) else color

    class ImportResult {
        var taskCount = 0
        var importCount = 0
        var skipCount = 0
    }

    class LegacyLocation {
        var name: String? = null
        var address: String? = null
        var phone: String? = null
        var url: String? = null
        var latitude = 0.0
        var longitude = 0.0
        var radius = 0
        var arrival = false
        var departure = false
    }
}