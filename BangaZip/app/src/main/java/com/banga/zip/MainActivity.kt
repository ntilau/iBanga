package com.banga.zip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var modeGroup: RadioGroup
    private lateinit var archiveRadio: RadioButton
    private lateinit var extractRadio: RadioButton
    private lateinit var sourceLayout: TextInputLayout
    private lateinit var sourcePathEdit: EditText
    private lateinit var destLayout: TextInputLayout
    private lateinit var destPathEdit: EditText
    private lateinit var passwordInputLayout: TextInputLayout
    private lateinit var passwordEdit: TextInputEditText
    private lateinit var confirmInputLayout: TextInputLayout
    private lateinit var confirmPasswordEdit: TextInputEditText
    private lateinit var rememberPwdCheck: CheckBox
    private lateinit var executeBtn: Button
    private lateinit var progressGroup: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressFiles: TextView
    private lateinit var currentFileLabel: TextView
    private lateinit var resultText: TextView
    private lateinit var cancelBtn: Button
    private lateinit var scrollView: ScrollView

    private lateinit var passwordStore: PasswordStore

    /** The running archive/extract job, so we can cancel it. */
    private var currentJob: Job? = null

    // Track what the SAF pickers returned so we can derive a display path.
    private var currentSourcePickerUri: Uri? = null
    private var currentDestPickerUri: Uri? = null

    // ---------------------------------------------------------------
    // Activity result launchers
    // ---------------------------------------------------------------

    private val sourcePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            currentSourcePickerUri = it
            // Take a temporary persistable permission so we can read the tree.
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            sourcePathEdit.setText(displayPathFromUri(it) ?: it.toString())
        }
    }

    private val destPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            currentDestPickerUri = it
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val raw = displayPathFromUri(it) ?: it.toString()
            // In archive mode the destination is a file path with .7z extension.
            destPathEdit.setText(
                if (archiveRadio.isChecked) "$raw/${raw.substringAfterLast('/')}.7z" else raw
            )
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from system Settings → re-check permission.
        updateExecuteButtonState()
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        passwordStore = PasswordStore(this)

        initViews()
        setupListeners()
        loadSavedPassword()
        updateModeLabels()
        checkStoragePermission()
    }

    // ---------------------------------------------------------------
    // View binding
    // ---------------------------------------------------------------

    private fun initViews() {
        modeGroup = findViewById(R.id.mode_group)
        archiveRadio = findViewById(R.id.archive_radio)
        extractRadio = findViewById(R.id.extract_radio)
        sourceLayout = findViewById(R.id.source_layout)
        sourcePathEdit = findViewById(R.id.source_path)
        destLayout = findViewById(R.id.dest_layout)
        destPathEdit = findViewById(R.id.dest_path)
        passwordInputLayout = findViewById(R.id.password_layout)
        passwordEdit = findViewById(R.id.password)
        confirmInputLayout = findViewById(R.id.confirm_layout)
        confirmPasswordEdit = findViewById(R.id.confirm_password)
        rememberPwdCheck = findViewById(R.id.remember_password)
        executeBtn = findViewById(R.id.execute_btn)
        progressGroup = findViewById(R.id.progress_group)
        progressBar = findViewById(R.id.progress_bar)
        progressFiles = findViewById(R.id.progress_files)
        currentFileLabel = findViewById(R.id.current_file)
        cancelBtn = findViewById(R.id.cancel_btn)
        resultText = findViewById(R.id.result_text)
        scrollView = findViewById(R.id.scroll_view)
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    private fun setupListeners() {
        sourceLayout.setEndIconOnClickListener {
            sourcePickerLauncher.launch(null)
        }
        destLayout.setEndIconOnClickListener {
            destPickerLauncher.launch(null)
        }

        executeBtn.setOnClickListener { executeOperation() }

        cancelBtn.setOnClickListener {
            currentJob?.let { job ->
                if (job.isActive) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.abort_title)
                        .setMessage(R.string.abort_message)
                        .setPositiveButton(R.string.abort_confirm) { _, _ ->
                            job.cancel()
                            cancelBtn.isEnabled = false
                            cancelBtn.text = getString(R.string.cancelling)
                        }
                        .setNegativeButton(R.string.abort_keep_going, null)
                        .show()
                }
            }
        }

        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeLabels() }

        // Auto-fill destination with <source_folder_name>.7z in archive mode.
        sourcePathEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!archiveRadio.isChecked) return
                val source = s?.toString()?.trim() ?: return
                if (source.isEmpty()) return
                // Only auto-fill if destination is currently empty.
                if (!destPathEdit.text.isNullOrEmpty()) return
                val srcFile = File(source)
                val name = srcFile.name
                val parent = srcFile.parent ?: return
                destPathEdit.setText("$parent/$name.7z")
            }
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) { /* no-op */ }

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) { /* no-op */ }
        })
    }

    private fun updateModeLabels() {
        val isArchive = archiveRadio.isChecked
        sourceLayout.hint = if (isArchive) getString(R.string.source_folder) else getString(R.string.source_archive)
        destLayout.hint = if (isArchive) getString(R.string.dest_archive) else getString(R.string.dest_folder)
        destPathEdit.setText("") // clear so user doesn't accidentally reuse
    }

    // ---------------------------------------------------------------
    // Password persistence
    // ---------------------------------------------------------------

    private fun loadSavedPassword() {
        if (!passwordStore.rememberPassword) return
        rememberPwdCheck.isChecked = true
        val saved = passwordStore.savedPassword
        if (!saved.isNullOrEmpty()) {
            passwordEdit.setText(saved)
            confirmPasswordEdit.setText(saved)
        }
    }

    // ---------------------------------------------------------------
    // SAF URI → human-readable path
    // ---------------------------------------------------------------

    private fun displayPathFromUri(uri: Uri): String? {
        if ("com.android.externalstorage.documents" !in (uri.authority.orEmpty())) {
            return null
        }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts.size < 2) return docId
            val storageType = parts[0]
            val relPath = parts.drop(1).joinToString(":")
            when (storageType) {
                "primary" -> "${Environment.getExternalStorageDirectory()}/$relPath"
                else -> "/storage/$storageType/$relPath"
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Storage permission
    // ---------------------------------------------------------------

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ needs MANAGE_EXTERNAL_STORAGE for path-based access.
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(
                    this,
                    "Grant \"All files access\" for path-based browsing",
                    Toast.LENGTH_LONG
                ).show()
                manageStorageLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // Android 10 and below: request legacy storage permissions.
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    REQ_STORAGE
                )
            }
        }
    }

    private fun updateExecuteButtonState() {
        // Button is always enabled; validation happens on click.
    }

    // ---------------------------------------------------------------
    // Core operation
    // ---------------------------------------------------------------

    private fun executeOperation() {
        // Don't start a new operation while one is running.
        if (currentJob?.isActive == true) return

        val sourcePath = sourcePathEdit.text.toString().trim()
        val destPath = destPathEdit.text.toString().trim()
        val password = passwordEdit.text?.toString().orEmpty()
        val confirmPassword = confirmPasswordEdit.text?.toString().orEmpty()

        // ---- Validation -------------------------------------------
        if (sourcePath.isEmpty()) {
            showResult("Please enter a source path", isError = true)
            sourcePathEdit.requestFocus()
            return
        }
        if (destPath.isEmpty()) {
            showResult("Please enter a destination path", isError = true)
            destPathEdit.requestFocus()
            return
        }

        val sourceFile = File(sourcePath)
        val destFile = File(destPath)

        if (archiveRadio.isChecked) {
            if (!sourceFile.exists() || !sourceFile.isDirectory) {
                showResult("Source folder does not exist: $sourcePath", isError = true)
                return
            }
            if (destFile.exists()) {
                // Overwrite: delete the existing file before proceeding.
                if (!destFile.delete()) {
                    showResult("Failed to delete existing destination: $destPath", isError = true)
                    return
                }
            }
            // Suggest .7z extension if missing.
            if (!destPath.lowercase().endsWith(".7z")) {
                showResult("Destination should end with .7z", isError = true)
                return
            }
        } else {
            if (!sourceFile.exists() || !sourceFile.isFile) {
                showResult("Source archive not found: $sourcePath", isError = true)
                return
            }
            if (!sourcePath.lowercase().endsWith(".7z")) {
                showResult("Only .7z archives are supported", isError = true)
                return
            }
            // Destination folder - it's OK if it doesn't exist, we'll create it.
        }

        // Password confirmation
        if (password != confirmPassword) {
            showResult("Passwords do not match", isError = true)
            return
        }

        // ---- Persist password if requested -------------------------
        if (rememberPwdCheck.isChecked && password.isNotEmpty()) {
            passwordStore.savedPassword = password
            passwordStore.rememberPassword = true
        } else {
            passwordStore.savedPassword = null
            passwordStore.rememberPassword = false
        }

        // ---- Execute -----------------------------------------------
        val isArchive = archiveRadio.isChecked
        val passwordArg = password.ifEmpty { null }

        showProgress()
        resultText.visibility = View.GONE

        currentJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val helper = ArchiveHelper()
                    if (isArchive) {
                        // Safety net: delete if file was recreated between validation and write.
                        File(destPath).delete()
                        helper.archiveFolder(
                            sourceFolder = sourcePath,
                            archivePath = destPath,
                            password = passwordArg,
                            onProgress = { cur, tot, name ->
                                runOnUiThread { updateProgress(cur, tot, name) }
                            }
                        )
                    } else {
                        helper.extractArchive(
                            archivePath = sourcePath,
                            destinationFolder = destPath,
                            password = passwordArg,
                            onProgress = { cur, tot, name ->
                                runOnUiThread { updateProgress(cur, tot, name) }
                            }
                        )
                    }
                }

                // Success
                hideProgress()
                val action = if (isArchive) "Archived" else "Extracted"
                showResult("$action successfully! → $destPath", isError = false)

            } catch (e: CancellationException) {
                // User aborted — delete partial output.
                if (isArchive) File(destPath).delete()
                hideProgress()
                showResult("Operation cancelled", isError = true)

            } catch (e: Exception) {
                hideProgress()
                val msg = when {
                    e.message?.contains("password", ignoreCase = true) == true ->
                        "Wrong password or corrupted archive"
                    e is IllegalArgumentException ->
                        e.message ?: "Invalid input"
                    else ->
                        "Error: ${e.message ?: "Unknown error"}"
                }
                showResult(msg, isError = true)
            } finally {
                currentJob = null
            }
        }
    }

    // ---------------------------------------------------------------
    // Progress / result UI
    // ---------------------------------------------------------------

    private fun showProgress() {
        progressGroup.visibility = View.VISIBLE
        executeBtn.isEnabled = false
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressFiles.text = getString(R.string.starting)
        currentFileLabel.text = ""
        resultText.visibility = View.GONE
    }

    private fun updateProgress(current: Int, total: Int, fileName: String) {
        if (total > 0) {
            progressBar.max = total
            progressBar.progress = current
            val pct = (current * 100L / total).toInt()
            progressFiles.text = getString(R.string.progress_format, current, total, pct)
        } else {
            progressBar.isIndeterminate = true
            progressFiles.text = getString(R.string.processing, current)
        }
        currentFileLabel.text = fileName
        // Auto-scroll to keep progress visible.
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideProgress() {
        progressGroup.visibility = View.GONE
        executeBtn.isEnabled = true
    }

    private fun showResult(message: String, isError: Boolean) {
        resultText.visibility = View.VISIBLE
        resultText.text = message
        resultText.setTextColor(
            if (isError) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.holo_green_dark)
        )
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
