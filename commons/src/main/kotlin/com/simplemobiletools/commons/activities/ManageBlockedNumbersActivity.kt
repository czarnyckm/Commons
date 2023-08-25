package com.simplemobiletools.commons.activities

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplemobiletools.commons.R
import com.simplemobiletools.commons.compose.extensions.onEventValue
import com.simplemobiletools.commons.compose.screens.ManageBlockedNumbersScreen
import com.simplemobiletools.commons.compose.theme.AppThemeSurface
import com.simplemobiletools.commons.dialogs.AddBlockedNumberDialog
import com.simplemobiletools.commons.dialogs.ExportBlockedNumbersDialog
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.BlockedNumber
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ManageBlockedNumbersActivity : BaseSimpleActivity() {

    private val config by lazy {
        baseConfig
    }

    private companion object {
        private const val PICK_IMPORT_SOURCE_INTENT = 11
        private const val PICK_EXPORT_FILE_INTENT = 21
    }

    override fun getAppIconIDs() = intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList()

    override fun getAppLauncherName() = intent.getStringExtra(APP_LAUNCHER_NAME) ?: ""

    private val manageBlockedNumbersViewModel by viewModels<ManageBlockedNumbersViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val blockedNumbers by manageBlockedNumbersViewModel.blockedNumbers.collectAsStateWithLifecycle()
            LaunchedEffect(blockedNumbers) {
                if (blockedNumbers.any { blockedNumber -> blockedNumber.number.isBlockedNumberPattern() }) {
                    maybeSetDefaultCallerIdApp()
                }
            }

            val isBlockingHiddenNumbers by config.isBlockingHiddenNumbers.collectAsStateWithLifecycle(initialValue = config.blockHiddenNumbers)
            val isBlockingUnknownNumbers by config.isBlockingUnknownNumbers.collectAsStateWithLifecycle(initialValue = config.blockUnknownNumbers)
            val isDialer = remember {
                config.appId.startsWith("com.simplemobiletools.dialer")
            }
            val isDefaultDialer: Boolean = onEventValue {
                context.isDefaultDialer()
            }
            AppThemeSurface {
                ManageBlockedNumbersScreen(
                    goBack = ::finish,
                    onAdd = ::addOrEditBlockedNumber,
                    onImportBlockedNumbers = ::tryImportBlockedNumbers,
                    onExportBlockedNumbers = ::tryExportBlockedNumbers,
                    setAsDefault = ::maybeSetDefaultCallerIdApp,
                    isDialer = isDialer,
                    hasGivenPermissionToBlock = isDefaultDialer,
                    isBlockUnknownSelected = isBlockingUnknownNumbers,
                    onBlockUnknownSelectedChange = { isChecked ->
                        config.blockUnknownNumbers = isChecked
                        onCheckedSetCallerIdAsDefault(isChecked)
                    },
                    isHiddenSelected = isBlockingHiddenNumbers,
                    onHiddenSelectedChange = { isChecked ->
                        config.blockHiddenNumbers = isChecked
                        onCheckedSetCallerIdAsDefault(isChecked)
                    },
                    blockedNumbers = blockedNumbers,
                    onDelete = { selectedKeys ->
                        deleteBlockedNumbers(blockedNumbers, selectedKeys)
                    },
                    onEdit = { blockedNumber ->
                        addOrEditBlockedNumber(currentNumber = blockedNumber)
                        manageBlockedNumbersViewModel.updateBlockedNumbers()
                    }
                ) { blockedNumber ->
                    copyToClipboard(blockedNumber.number)
                }
            }
        }
    }

    private fun deleteBlockedNumbers(
        blockedNumbers: ImmutableList<BlockedNumber>,
        selectedKeys: Set<Long>
    ) {
        blockedNumbers.filter { selectedKeys.contains(it.id) }
            .forEach {
                deleteBlockedNumber(it.number)
            }
        manageBlockedNumbersViewModel.updateBlockedNumbers()
    }

    private fun tryImportBlockedNumbers() {
        if (isQPlus()) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"

                try {
                    startActivityForResult(this, PICK_IMPORT_SOURCE_INTENT)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        } else {
            handlePermission(PERMISSION_READ_STORAGE) {
                if (it) {
                    pickFileToImportBlockedNumbers()
                }
            }
        }
    }

    private fun pickFileToImportBlockedNumbers() {
        FilePickerDialog(this) {
            importBlockedNumbers(it)
        }
    }

    private fun tryImportBlockedNumbersFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> importBlockedNumbers(uri.path!!)
            "content" -> {
                val tempFile = getTempFile("blocked", "blocked_numbers.txt")
                if (tempFile == null) {
                    toast(R.string.unknown_error_occurred)
                    return
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val out = FileOutputStream(tempFile)
                    inputStream!!.copyTo(out)
                    importBlockedNumbers(tempFile.absolutePath)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> toast(R.string.invalid_file_format)
        }
    }

    private fun importBlockedNumbers(path: String) {
        ensureBackgroundThread {
            val result = BlockedNumbersImporter(this).importBlockedNumbers(path)
            toast(
                when (result) {
                    BlockedNumbersImporter.ImportResult.IMPORT_OK -> R.string.importing_successful
                    BlockedNumbersImporter.ImportResult.IMPORT_FAIL -> R.string.no_items_found
                }
            )
            updateBlockedNumbers()
        }
    }

    private fun addOrEditBlockedNumber(currentNumber: BlockedNumber? = null) {
        AddBlockedNumberDialog(this, currentNumber) {
            updateBlockedNumbers()
        }
    }

    private fun updateBlockedNumbers() {
        manageBlockedNumbersViewModel.updateBlockedNumbers()
    }

    private fun onCheckedSetCallerIdAsDefault(isChecked: Boolean) {
        if (isChecked) {
            maybeSetDefaultCallerIdApp()
        }
    }

    private fun maybeSetDefaultCallerIdApp() {
        if (isQPlus() && baseConfig.appId.startsWith("com.simplemobiletools.dialer")) {
            setDefaultCallerIdApp()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        when {
            requestCode == REQUEST_CODE_SET_DEFAULT_DIALER && isDefaultDialer() -> {
                updateBlockedNumbers()
            }

            requestCode == PICK_IMPORT_SOURCE_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null -> {
                tryImportBlockedNumbersFromFile(resultData.data!!)
            }

            requestCode == PICK_EXPORT_FILE_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null -> {
                val outputStream = contentResolver.openOutputStream(resultData.data!!)
                exportBlockedNumbersTo(outputStream)
            }

            requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK -> {
                toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
                baseConfig.blockUnknownNumbers = false
                baseConfig.blockHiddenNumbers = false

            }
        }
    }

    private fun exportBlockedNumbersTo(outputStream: OutputStream?) {
        ensureBackgroundThread {
            val blockedNumbers = getBlockedNumbers()
            if (blockedNumbers.isEmpty()) {
                toast(R.string.no_entries_for_exporting)
            } else {
                BlockedNumbersExporter.exportBlockedNumbers(blockedNumbers, outputStream) {
                    toast(
                        when (it) {
                            ExportResult.EXPORT_OK -> R.string.exporting_successful
                            else -> R.string.exporting_failed
                        }
                    )
                }
            }
        }
    }

    private fun tryExportBlockedNumbers() {
        if (isQPlus()) {
            ExportBlockedNumbersDialog(this, baseConfig.lastBlockedNumbersExportPath, true) { file ->
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        startActivityForResult(this, PICK_EXPORT_FILE_INTENT)
                    } catch (e: ActivityNotFoundException) {
                        toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    ExportBlockedNumbersDialog(this, baseConfig.lastBlockedNumbersExportPath, false) { file ->
                        getFileOutputStream(file.toFileDirItem(this), true) { out ->
                            exportBlockedNumbersTo(out)
                        }
                    }
                }
            }
        }
    }

    class ManageBlockedNumbersViewModel(private val application: Application) : AndroidViewModel(application) {
        private val _blockedNumbers = MutableStateFlow(application.getBlockedNumbers().toImmutableList())
        val blockedNumbers = _blockedNumbers.asStateFlow()
        fun updateBlockedNumbers() {
            _blockedNumbers.update { application.getBlockedNumbers().toImmutableList() }
        }

        init {
            application.getBlockedNumbers()
        }
    }
}
