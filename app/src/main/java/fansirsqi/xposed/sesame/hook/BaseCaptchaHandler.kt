package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import fansirsqi.xposed.sesame.hook.simple.MotionEventSimulator
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager
import fansirsqi.xposed.sesame.hook.simple.SimpleViewImage
import fansirsqi.xposed.sesame.hook.simple.ViewHierarchyAnalyzer
import fansirsqi.xposed.sesame.hook.simple.SliderTFLite
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.ByteArrayOutputStream
import kotlin.random.Random

import fansirsqi.xposed.sesame.hook.VersionHook
import fansirsqi.xposed.sesame.entity.AlipayVersion

/*
 * 滑动坐标四元组，用于封装滑动起点和终点坐标。
 */
data class SlideCoordinates(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

/**
 * 验证码处理程序的基类，提供处理滑动验证码的通用逻辑。
 */
abstract class BaseCaptchaHandler {

    companion object {
        private const val TAG = "CaptchaHandler"

        // 滑动参数配置
        private const val SLIDE_START_OFFSET = 25
        private const val SLIDE_END_MARGIN = 20
        private const val SLIDE_DURATION_MIN = 500L
        private const val SLIDE_DURATION_MAX = 800L //稍微增加最大时间
        private const val POST_SLIDE_CHECK_DELAY_MS = 2000L

        // 旧版本 XPath
        private const val OLD_SLIDE_VERIFY_TEXT_XPATH = "//TextView[contains(@text,'向右滑动验证')]"
        
        // 新版本 XPath (根据提供的截图)
        private const val NEW_SLIDE_VERIFY_TEXT_XPATH = "//TextView[contains(@text,'请拖动滑块完成拼图')]"
        
        private val captchaProcessingMutex = Mutex()
    }

    protected abstract fun getSlidePathKey(): String

    private var sliderDetector: SliderTFLite? = null

    open suspend fun handleActivity(activity: Activity, root: SimpleViewImage): Boolean {
        return try {

            // 版本判断逻辑
            val isNewVersion = if (VersionHook.hasVersion()) {
                val currentVersion = VersionHook.getCapturedVersion() ?: AlipayVersion("")
                val thresholdVersion = AlipayVersion("10.6.58.9999") 
                currentVersion.compareTo(thresholdVersion) > 0
            } else {
                false
            }

            if (isNewVersion) {
                Log.record(TAG, "检测到新版本应用，使用图像识别模式处理验证码。")
                handleNewVersionCaptcha(activity)
            } else {
                Log.record(TAG, "检测到旧版本应用，使用传统模式处理验证码。")
                handleLegacySlideCaptcha(activity)
            }
        } catch (e: Exception) {
            Log.error(TAG, "处理验证码页面时发生异常: ${e.stackTraceToString()}")
            false
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun handleNewVersionCaptcha(activity: Activity): Boolean {
        if (!captchaProcessingMutex.tryLock()) return true
        try {
            if (sliderDetector == null) {
                sliderDetector = SliderTFLite(activity.applicationContext)
            }

            // 1. 查找新版提示文本
            val verifyText = SimplePageManager.tryGetTopView(NEW_SLIDE_VERIFY_TEXT_XPATH) ?: run {
                // 如果没找到新版文本，尝试找旧版文本作为回退
                if (SimplePageManager.tryGetTopView(OLD_SLIDE_VERIFY_TEXT_XPATH) != null) {
                    Log.record(TAG, "未找到新版文本但发现了旧版文本，回退到旧逻辑。")
                    captchaProcessingMutex.unlock() // 解锁以便调用旧逻辑
                    return handleLegacySlideCaptcha(activity)
                }
                Log.record(TAG, "未找文本文本!!!")
                return false
            }

            Log.record(TAG, "发现新版滑动验证文本: ${verifyText.getText()}")
            delay(800L) // 等待图片加载完全

            // 2. 查找关键视图：滑块(Slider) 和 背景图(Background)
            // 这里需要根据实际布局层级调整。通常背景图是滑块的兄弟节点或父容器的子节点
            val sliderView = ViewHierarchyAnalyzer.findActualSliderView(verifyText) ?: return false
            val backgroundView = findCaptchaImageView(sliderView) ?: run {
                Log.record(TAG, "无法找到验证码背景图片视图")
                return false
            }

            // 3. 截图并识别
            val (gapX, conf) = recognizeCaptchaGapNative(backgroundView) ?: run {
                Log.record(TAG, "图像识别失败")
                return false
            }
            
            // 4. 计算坐标并滑动
            // gapX 是图片内部的缺口左边缘 X 坐标
            val slideDistance = calculateDistance(gapX, backgroundView.width, backgroundView, sliderView)
            
            return performSlide(activity, sliderView, slideDistance)

        } catch (e: Exception) {
            Log.record(TAG, "新版验证码处理出错: ${e.stackTraceToString()}")
            return false
        } finally {
            if (captchaProcessingMutex.isLocked) captchaProcessingMutex.unlock()
        }
    }

    /*
     * 在 sliderView 附近查找大的 ImageView (验证码背景)
     * 策略：向上找父容器，然后在父容器中找尺寸最大的 ImageView
     */
    private fun findCaptchaImageView(sliderView: View): ImageView? {
        val parent = sliderView.parent as? ViewGroup ?: return null
        // 简单策略：遍历父容器子View，找面积最大的 ImageView
        var maxArea = 0
        var targetImage: ImageView? = null
        
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImageView && child.visibility == View.VISIBLE) {
                val area = child.width * child.height
                // 通常验证码图片高度会比较大，且不是小图标
                if (area > maxArea && child.width > 200) { 
                    maxArea = area
                    targetImage = child
                }
            }
        }
        return targetImage
    }

    private fun recognizeCaptchaGapNative(imageView: ImageView): Pair<Int, Float>? {
        return try {
            val bitmap = getBitmapFromView(imageView) ?: return null

            // 调用 Kotlin 实现的 TFLite 识别
            val (x1, conf) = sliderDetector!!.identifyOffset(bitmap)

            Log.record(TAG, "TFLite 识别成功: x1=$x1, conf=$conf")

            // 注意：identifyOffset 返回的 x1 是相对于传入 bitmap 的坐标
            // 如果 bitmap 是直接从 View 截取的，那么这个 x1 就是 View 坐标系下的
            return Pair(x1, conf)
        } catch (e: Exception) {
            Log.record(TAG, "TFLite 调用失败: ${e.message}")
            null
        }
    }


    private fun getBitmapFromView(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun calculateDistance(gapXInImage: Int, imageRealWidth: Int, bgView: View, sliderView: View): Float {
        // 因为我们传入的是 View 的截图 (getBitmapFromView)，所以 gapXInImage 已经是屏幕坐标系下的像素值
        // imageRealWidth 传入的是 bgView.width
        // 所以 scale 应该是 1.0
        val scale = bgView.width.toFloat() / imageRealWidth.toFloat()

        // 这里的计算可以简化，直接认为 gapXInImage 就是目标位置
        val distance = gapXInImage.toFloat()

        Log.record(TAG, "计算距离: GapX=$gapXInImage, Distance=$distance")
        return distance
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun handleLegacySlideCaptcha(activity: Activity): Boolean {
        if (!captchaProcessingMutex.tryLock()) {
            return true
        }
        try {
            val slideTextInDialog = SimplePageManager.tryGetTopView(OLD_SLIDE_VERIFY_TEXT_XPATH) ?: run {
                return false
            }
            Log.record(TAG, "发现旧版滑动验证文本: ${slideTextInDialog.getText()}")
            delay(500L)
            
            // 使用旧版盲猜逻辑
            val sliderView = ViewHierarchyAnalyzer.findActualSliderView(slideTextInDialog) ?: return false
            val (startX, startY, endX, endY) = calculateLegacySlideCoordinates(activity, sliderView) ?: return false
            
            return executeSlide(sliderView, startX, startY, endX, endY)
        } catch (e: Exception) {
            Log.record(TAG, "旧版处理出错: ${e.stackTraceToString()}")
            return false
        } finally {
            captchaProcessingMutex.unlock()
        }
    }

    /*
     * 计算滑动验证码的坐标参数。
     * 
     * @param activity 当前Activity，用于获取屏幕信息
     * @param sliderView 滑块视图
     * @return 包含(startX, startY, endX, endY)的四元组，如果计算失败返回null
     */
    private fun calculateLegacySlideCoordinates(activity: Activity, sliderView: android.view.View): SlideCoordinates? {
        // 获取滑动区域的整体容器（滑块的父容器）
        val slideContainer = sliderView.parent as? android.view.ViewGroup ?: run {
          //  Log.captcha(TAG, "未能找到滑块容器")
            return null
        }
        
        // 获取屏幕尺寸信息
        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // 计算滑动区域的边界
        val containerLocation = IntArray(2)
        slideContainer.getLocationOnScreen(containerLocation)
        val containerX = containerLocation[0]
        val containerY = containerLocation[1]
        val containerWidth = slideContainer.width
        val containerHeight = slideContainer.height

        // 计算滑块位置
        val sliderLocation = IntArray(2)
        sliderView.getLocationOnScreen(sliderLocation)
        val sliderX = sliderLocation[0]
        val sliderY = sliderLocation[1]
        val sliderWidth = sliderView.width
        val sliderHeight = sliderView.height

        // 计算滑动起点（滑块中心稍微偏右，模拟手指按住滑块）
        val startX = sliderX + sliderWidth / 2f + SLIDE_START_OFFSET.toFloat() + Random.nextInt(-3, 4) // 添加随机偏移
        val startY = sliderY + sliderHeight / 2f + Random.nextInt(-2, 3)

        // 计算滑动终点
        val containerRightEdge = containerX + containerWidth
        val maxEndX = screenWidth - 50f // 距离屏幕右边缘50像素
        
        // 计算理想的滑动终点（容器右端减去边距）
        var endX = containerRightEdge - SLIDE_END_MARGIN.toFloat() + Random.nextInt(-5, 6) // 添加随机偏移
        
        // 确保滑动终点不超过屏幕边界
        if (endX > maxEndX) {
            endX = maxEndX
            Log.record(TAG, "调整滑动终点以适配屏幕边界")
        }
        // 确保滑动距离足够（至少滑块宽度的1.5倍）
        val minSlideDistance = sliderWidth * 1.5f
        val actualSlideDistance = endX - startX
        if (actualSlideDistance < minSlideDistance) {
            endX = startX + minSlideDistance + Random.nextInt(-3, 4) // 添加随机偏移
            Log.record(TAG, "调整滑动距离至最小要求: ${minSlideDistance}px")
        }
        val endY = startY // 保持水平滑动
        // 输出详细的调试信息
        Log.record(TAG, "屏幕信息: 尺寸=${screenWidth}x$screenHeight")
        Log.record(TAG, "滑动区域信息: 容器位置=[$containerX,$containerY], 尺寸=${containerWidth}x$containerHeight")
        Log.record(TAG, "滑块信息: 位置=[$sliderX,$sliderY], 尺寸=${sliderWidth}x${sliderHeight}")
        Log.record(TAG, "计算结果: 起点=[$startX,$startY], 终点=[$endX,$endY], 滑动距离=${endX-startX}px")

        return SlideCoordinates(startX, startY, endX, endY)
    }
    
    // 用于新版逻辑：根据计算出的距离滑动
    private suspend fun performSlide(activity: Activity, sliderView: View, distance: Float): Boolean {
        val location = IntArray(2)
        sliderView.getLocationOnScreen(location)
        val startX = location[0] + sliderView.width / 2f
        val startY = location[1] + sliderView.height / 2f
        
        // 计算终点
        val endX = startX + distance
        val endY = startY + Random.nextInt(-2, 3) // 微小抖动

        return executeSlide(sliderView, startX, startY, endX, endY)
    }

    // 真正的滑动执行和结果检查
    private suspend fun executeSlide(sliderView: View, startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val slideDuration = Random.nextLong(SLIDE_DURATION_MIN, SLIDE_DURATION_MAX + 1)
        
        Log.record(TAG, "执行滑动: ($startX, $startY) -> ($endX, $endY), 时长: $slideDuration")

        ApplicationHook.sendBroadcastShell(
            getSlidePathKey(),
            "input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $slideDuration"
        )
        
        MotionEventSimulator.simulateSwipe(
            view = sliderView,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = slideDuration
        )

        delay(POST_SLIDE_CHECK_DELAY_MS)
        return checkCaptchaTextGone()
    }

    private fun checkCaptchaTextGone(): Boolean {
        // 检查旧版和新版文本是否都不存在了
        val oldText = SimplePageManager.tryGetTopView(OLD_SLIDE_VERIFY_TEXT_XPATH)
        val newText = SimplePageManager.tryGetTopView(NEW_SLIDE_VERIFY_TEXT_XPATH)
        
        return if (oldText == null && newText == null) {
            Log.record(TAG, "验证码文本已消失，滑动成功。")
            true
        } else {
            Log.record(TAG, "验证码文本仍然存在，滑动可能失败。")
            false
        }
    }
}