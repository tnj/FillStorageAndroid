package sh.nothing.fillstorage

import android.Manifest
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.File
import java.io.RandomAccessFile
import java.lang.Exception
import kotlin.coroutines.CoroutineContext

@RuntimePermissions
class MainActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fillStorageButton.setOnClickListener { onFillStorageClickWithPermissionCheck() }
        resetButton.setOnClickListener { onResetClickWithPermissionCheck() }

        job = Job()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onResume() {
        super.onResume()
        launch { updateFreeSpace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onFillStorageClick() {
        job = launch {
            fillStorageButton.isEnabled = false
            try {
                val remaining = fillStorage()
                if (remaining > 0) {
                    toast("Stopped before disk full")
                }
            } catch (e: CancellationException) {
                toast( "Cancelled")
            }
            withContext(NonCancellable) {
                updateFreeSpace()
                fillStorageButton.isEnabled = true
            }
        }
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onResetClick() {
        if (job.isActive) {
            job.cancel()
            return
        }

        launch {
            resetButton.isEnabled = false
            reset()
            updateFreeSpace()
            resetButton.isEnabled = true
        }
    }

    suspend fun updateFreeSpace() = withContext(Dispatchers.Main) {
        textBytes.text = Formatter.formatFileSize(this@MainActivity, getFreeBytes())
    }

    @WorkerThread
    suspend fun fillStorage() = withContext(Dispatchers.IO) {
        val maxFileSize = 4L * 1024 * 1024 * 1024

        val buffer = ByteArray(16 * 1024 * 1024)
        var size = getFreeBytes() - 100 * 1024 * 1024 // 100MB
        var iterations = 0
        var running = true

        while (running && size > 0) {
            val f = getDummyFile(iterations)
            val raf = RandomAccessFile(f, "rw")
            try {
                var sizeToBeCreated = Math.min(size, maxFileSize) - raf.length()
                raf.seek(raf.length())

                Log.i("dummy", "creating file ${f.absolutePath} in $sizeToBeCreated size")
                while (sizeToBeCreated > 0) {
                    val toBeWritten = Math.min(sizeToBeCreated, buffer.size.toLong()).toInt()
                    try {
                        raf.write(buffer, 0, toBeWritten)
                        sizeToBeCreated -= toBeWritten
                        size -= toBeWritten
                    } catch (e: Exception) {
                        Log.w("dummy", "error occured, aborting", e)
                        running = false
                        break
                    }
                    updateFreeSpace()
                }
            } finally {
                withContext(NonCancellable) {
                    raf.close()
                }
            }
            iterations++
        }
        size
    }

    @WorkerThread
    suspend fun reset() = withContext(Dispatchers.IO) {
        var iterations = 0
        while (true) {
            val file = getDummyFile(iterations++)
            if (!file.delete()) break
            Log.i("dummy", "file ${file.absolutePath} deleted")
        }
    }

    @WorkerThread
    suspend fun getFreeBytes() = withContext(Dispatchers.IO) {
        getDestDir()?.freeSpace ?: 0
    }

    private fun getDummyFile(i: Int = 0) = File(getDestDir(), "dummy%d".format(i))

    private fun getDestDir() = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)

    private fun toast(text: String) = Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
}
