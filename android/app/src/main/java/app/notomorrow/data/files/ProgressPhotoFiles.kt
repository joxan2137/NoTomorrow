package app.notomorrow.data.files

import java.io.File

/**
 * `ProgressPhotoStore` — the folder the progress photo JPEGs live in: `filesDir/progress_photos/`,
 * the app's private storage (no permission, not visible to other apps, removed with the app).
 * Tests pass a temporary [directory]. Blocking file I/O: call it off the main thread.
 */
class ProgressPhotoFiles(val directory: File) {

    fun file(fileName: String): File = File(directory, fileName)

    /** Writes [bytes] to a temporary file and renames it into place, like iOS's `.atomic` write. */
    fun write(bytes: ByteArray, fileName: String) {
        if (!directory.isDirectory && !directory.mkdirs()) error("Cannot create $directory")
        val target = file(fileName)
        val temp = File(directory, "$fileName.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Cannot write $target")
        }
    }

    fun remove(fileName: String) {
        file(fileName).delete()
    }

    /** Every photo file, gone (the account wipe). */
    fun deleteAll() {
        directory.deleteRecursively()
    }

    /** File names in the folder, sorted; empty when there is none. */
    fun fileNames(): List<String> = directory.list()?.sorted().orEmpty()

    companion object {
        const val DIRECTORY = "progress_photos"

        fun inFilesDir(filesDir: File): ProgressPhotoFiles = ProgressPhotoFiles(File(filesDir, DIRECTORY))
    }
}
