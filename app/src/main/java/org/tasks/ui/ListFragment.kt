package org.tasks.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import butterknife.BindView
import com.google.android.material.chip.ChipGroup
import com.todoroo.astrid.activity.TaskEditFragment
import com.todoroo.astrid.api.CaldavFilter
import com.todoroo.astrid.api.Filter
import com.todoroo.astrid.api.GtasksFilter
import com.todoroo.astrid.data.Task
import com.todoroo.astrid.service.TaskMover
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.R
import org.tasks.activities.ListPicker
import javax.inject.Inject

@AndroidEntryPoint
class ListFragment : TaskEditControlFragment() {
    @BindView(R.id.chip_group)
    lateinit var chipGroup: ChipGroup

    @Inject lateinit var taskMover: TaskMover
    @Inject lateinit var chipProvider: ChipProvider
    
    private var originalList: Filter? = null
    private lateinit var selectedList: Filter
    private lateinit var callback: OnListChanged

    interface OnListChanged {
        fun onListChanged(filter: Filter?)
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        callback = activity as OnListChanged
    }

    override fun createView(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            originalList = requireArguments().getParcelable(TaskEditFragment.EXTRA_LIST)!!
            setSelected(originalList!!)
        } else {
            originalList = savedInstanceState.getParcelable(EXTRA_ORIGINAL_LIST)
            setSelected(savedInstanceState.getParcelable(EXTRA_SELECTED_LIST)!!)
        }
    }

    private fun setSelected(filter: Filter) {
        selectedList = filter
        refreshView()
        callback.onListChanged(filter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(EXTRA_ORIGINAL_LIST, originalList)
        outState.putParcelable(EXTRA_SELECTED_LIST, selectedList)
    }

    override val layout: Int
        get() = R.layout.control_set_remote_list

    override val icon: Int
        get() = R.drawable.ic_list_24px

    override fun controlId() = TAG

    override fun onRowClick() = openPicker()

    override val isClickable: Boolean
        get() = true

    private fun openPicker() =
            ListPicker.newListPicker(selectedList, this, REQUEST_CODE_SELECT_LIST)
                    .show(parentFragmentManager, FRAG_TAG_GOOGLE_TASK_LIST_SELECTION)

    override fun requiresId() = true

    override suspend fun apply(task: Task) {
        if (isNew || hasChanges()) {
            task.parent = 0
            taskMover.move(listOf(task.id), selectedList)
        }
    }

    override suspend fun hasChanges(original: Task) = hasChanges()

    private fun hasChanges() = selectedList != originalList

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SELECT_LIST) {
            if (resultCode == Activity.RESULT_OK) {
                data?.getParcelableExtra<Filter>(ListPicker.EXTRA_SELECTED_FILTER)?.let {
                    setList(it)
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun setList(list: Filter) {
        if (list is GtasksFilter || list is CaldavFilter) {
            setSelected(list)
        } else {
            throw RuntimeException("Unhandled filter type")
        }
    }

    private fun refreshView() {
        chipGroup.removeAllViews()
        val chip = chipProvider.newChip(selectedList, R.drawable.ic_list_24px, showText = true, showIcon = true)!!
        chip.setOnClickListener { openPicker() }
        chipGroup.addView(chip)
    }

    companion object {
        const val TAG = R.string.TEA_ctrl_google_task_list
        private const val FRAG_TAG_GOOGLE_TASK_LIST_SELECTION = "frag_tag_google_task_list_selection"
        private const val EXTRA_ORIGINAL_LIST = "extra_original_list"
        private const val EXTRA_SELECTED_LIST = "extra_selected_list"
        private const val REQUEST_CODE_SELECT_LIST = 10101
    }
}