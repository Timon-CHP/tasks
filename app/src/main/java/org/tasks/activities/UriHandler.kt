package org.tasks.activities

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.todoroo.astrid.dao.TaskDaoBlocking
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.tasks.intents.TaskIntents
import org.tasks.provider.TasksContentProvider
import org.tasks.provider.TasksContentProvider.Companion.URI_OPEN_TASK
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class UriHandler : AppCompatActivity() {

    @Inject lateinit var taskDao: TaskDaoBlocking

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (TasksContentProvider.URI_MATCHER.match(intent.data ?: Uri.EMPTY)) {
            URI_OPEN_TASK -> {
                val id = intent.data?.lastPathSegment?.toLongOrNull() ?: 0
                if (id > 0) {
                    Single.fromCallable { Optional.ofNullable(taskDao.fetchBlocking(id))}
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .doAfterTerminate(this::finish)
                            .subscribe(
                                    { startActivity(TaskIntents.getEditTaskIntent(this, it.get())) },
                                    Timber::e)
                } else {
                    startActivity(TaskIntents.getNewTaskIntent(this, null))
                }
            }
            else -> {
                Timber.w("Invalid uri: ${intent.data}")
                finish()
            }
        }
    }
}