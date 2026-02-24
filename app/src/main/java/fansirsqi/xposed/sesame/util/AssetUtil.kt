package fansirsqi.xposed.sesame.util

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 用于处理Assets资源文件的工具类
 */
object AssetUtil {
    private val TAG: String = AssetUtil::class.java.simpleName
    const val CHEKCE_SO = "libchecker.so"
    const val DEXKIT_OS = "libdexkit.so"
    const val TFLITE_JNI = "libtensorflowlite_jni.so"
    const val TFLITE_GPU_JNI = "libtensorflowlite_gpu_jni.so"
    const val SLIDER_MODEL = "slider.tflite" // 模型文件名
    private var destDir: String = Files.MAIN_DIR.absolutePath + File.separator + "lib"
    var checkerDestFile: File = File(destDir, CHEKCE_SO)
    var dexkitDestFile: File = File(destDir, DEXKIT_OS)
    var tfliteDestFile: File = File(destDir, TFLITE_JNI)
    var tfliteGpuDestFile: File = File(destDir, TFLITE_GPU_JNI)
    var sliderModelDestFile: File = File(destDir, SLIDER_MODEL)
    var modelPrivateFile: File? = null

    private fun compareMD5(file1: String, file2: String): Boolean {
        try {
            val md51 = getMD5(file1)
            val md52 = getMD5(file2)
            if (md51 == null || md52 == null || md51.isEmpty() || md52.isEmpty()) {
                return false
            }
            return md51 == md52
        } catch (e: Exception) {
            Log.error(TAG, "Failed to compare MD5: " + e.message)
            return false
        }
    }


    private fun getMD5(filePath: String): String? {
        try {
            val file = File(filePath)
            if (!file.isFile) {
                return null // 文件无效时返回null
            }
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { `in` ->  // 使用try-with-resources
                val buffer = ByteArray(1024)
                var len: Int
                while ((`in`.read(buffer).also { len = it }) != -1) {
                    digest.update(buffer, 0, len)
                }
            }
            val digestBytes = digest.digest()
            return BigInteger(1, digestBytes).toString(16)
        } catch (e: Exception) {
            Log.error(TAG, "Failed to get MD5: " + e.message)
            return null // 异常时返回null
        }
    }


    /**
     * 从应用安装目录复制so库到模块私有目录
     *
     * @param context 上下文
     * @param destFile  目标so库文件
     * @return 复制是否成功
     */
    fun copySoFileToStorage(context: Context, destFile: File): Boolean {
        try {
            Files.ensureDir(File(destDir))
            val appInfo = context.applicationInfo
            val sourceDir = appInfo.nativeLibraryDir + File.separator + destFile.name
            Log.record(TAG, "Copying SO file from $sourceDir to ${destFile.absolutePath}")
            if (destFile.exists() && compareMD5(sourceDir, destFile.absolutePath)) {
                return true
            }
            FileInputStream(sourceDir).use { fis ->
                FileOutputStream(destFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while ((fis.read(buffer).also { length = it }) > 0) {
                        fos.write(buffer, 0, length)
                    }
                    fos.flush()
                    Log.record(
                        TAG,
                        "Copied ${destFile.name} from $sourceDir ${checkerDestFile.absolutePath}"
                    )
                    setExecutablePermissions(destFile)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "Failed to copy SO file: " + e.message)
            return false
        }
    }

    /**
     * 从模块私有目录复制so库到应用安装目录
     * @param context 上下文
     * @param sourceFile  源so库文件
     * @return 复制是否成功
     */
    fun copyStorageSoFileToPrivateDir(context: Context, sourceFile: File): File? {
        try {
            if (!sourceFile.exists()) {
                Log.error(TAG, "SO file not exists: " + sourceFile.absolutePath)
                return null
            }
            val targetDir = context.getDir("sesame_libs", Context.MODE_PRIVATE)
            val targetFile = File(targetDir, sourceFile.name)
            if (targetFile.exists() && compareMD5(
                    sourceFile.absolutePath,
                    targetFile.absolutePath
                )
            ) {
                return targetFile
            }
            FileInputStream(sourceFile).use { fis ->
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while ((fis.read(buffer).also { length = it }) > 0) {
                        fos.write(buffer, 0, length)
                    }
                    fos.flush()
                    Log.record(TAG, "Copied ${sourceFile.name} from ${sourceFile.absolutePath} to ${targetFile.absolutePath}")
                    setExecutablePermissions(targetFile)
                    return targetFile
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "Failed to copy ${sourceFile.name} of storage: ${e.message}")
            return null
        }
    }

    private fun getStreamMD5(inputStream: InputStream): String? {
        try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1024)
            var len: Int
            while ((inputStream.read(buffer).also { len = it }) != -1) {
                digest.update(buffer, 0, len)
            }
            val digestBytes = digest.digest()
            return BigInteger(1, digestBytes).toString(16)
        } catch (e: Exception) {
            Log.error(TAG, "Failed to get Stream MD5: " + e.message)
            return null
        }
    }

    fun copyAssetToStorage(context: Context, assetName: String, destFile: File): Boolean {
        try {
            Files.ensureDir(File(destDir))

            // 如果目标文件已存在，对比 MD5
            if (destFile.exists()) {
                val assetMD5 = context.assets.open(assetName).use { getStreamMD5(it) }
                val fileMD5 = getMD5(destFile.absolutePath)
                if (assetMD5 != null && assetMD5 == fileMD5) {
                    return true // MD5 一致，无需复制
                }
            }

            Log.record(TAG, "Copying Asset file from $assetName to ${destFile.absolutePath}")

            context.assets.open(assetName).use { fis ->
                FileOutputStream(destFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while ((fis.read(buffer).also { length = it }) > 0) {
                        fos.write(buffer, 0, length)
                    }
                    fos.flush()
                    Log.record(TAG, "Copied $assetName to ${destFile.absolutePath}")
                    setExecutablePermissions(destFile)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "Failed to copy Asset file: " + e.message)
            return false
        }
    }

    fun copyStorageModelToPrivateDir(context: Context, sourceFile: File): File? {
        try {
            if (!sourceFile.exists()) {
                Log.error(TAG, "Model file not exists: " + sourceFile.absolutePath)
                return null
            }
            // 目标目录名为 sesame_models
            val targetDir = context.getDir("sesame_models", Context.MODE_PRIVATE)
            val targetFile = File(targetDir, sourceFile.name)

            // 对比 MD5
            if (targetFile.exists() && compareMD5(
                    sourceFile.absolutePath,
                    targetFile.absolutePath
                )
            ) {
                // 赋值给全局变量
                modelPrivateFile = targetFile
                return targetFile
            }

            FileInputStream(sourceFile).use { fis ->
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while ((fis.read(buffer).also { length = it }) > 0) {
                        fos.write(buffer, 0, length)
                    }
                    fos.flush()
                    Log.record(TAG, "Copied model ${sourceFile.name} to ${targetFile.absolutePath}")
                    setExecutablePermissions(targetFile)

                    // 赋值给全局变量
                    modelPrivateFile = targetFile
                    return targetFile
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "Failed to copy model ${sourceFile.name}: ${e.message}")
            return null
        }
    }

    /**
     * 设置目标文件的执行权限
     *
     * @param file so库文件
     */
    private fun setExecutablePermissions(file: File) {
        try {
            if (file.exists()) {
                val execSuccess = file.setExecutable(true, false)
                @SuppressLint("SetWorldReadable") val readSuccess = file.setReadable(true, false)
                if (!execSuccess) {
                    Log.error(TAG, "Failed to set executable permission for " + file.absolutePath)
                }
                if (!readSuccess) {
                    Log.error(TAG, "Failed to set readable permission for " + file.absolutePath)
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "Failed to set file permissions: " + e.message)
        }
    }
}
