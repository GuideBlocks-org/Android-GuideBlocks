package com.contextu.al.barcodescanner.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.Parameters
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.contextu.al.R
import com.contextu.al.common.utils.CameraUtils
import com.contextu.al.common.utils.PreferenceUtils
import com.google.android.gms.common.images.Size
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics). This
 * receives preview frames from the camera at a specified rate, sends those frames to detector as
 * fast as it is able to process.
 *
 *
 * This camera source makes a best effort to manage processing on preview frames as fast as
 * possible, while at the same time minimizing lag. As such, frames may be dropped if the detector
 * is unable to keep up with the rate of frames generated by the camera.
 */
@Suppress("DEPRECATION")
class CameraSource(private val graphicOverlay: GraphicOverlay) {

    private var camera: Camera? = null
    private var rotationDegrees: Int = 0

    /** Returns the preview size that is currently in use by the underlying camera.  */
    internal var previewSize: Size? = null
        private set

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private var processingThread: Thread? = null
    private val processingRunnable = FrameProcessingRunnable()

    private val processorLock = Object()
    private var frameProcessor: FrameProcessor? = null

    /**
     * Map to convert between a byte array, received from the camera, and its associated byte buffer.
     * We use byte buffers internally because this is a more efficient way to call into native code
     * later (avoids a potential copy).
     *
     *
     * **Note:** uses IdentityHashMap here instead of HashMap because the behavior of an array's
     * equals, hashCode and toString methods is both useless and unexpected. IdentityHashMap enforces
     * identity ('==') check on the keys.
     */
    private val bytesToByteBuffer = IdentityHashMap<ByteArray, ByteBuffer>()
    private val context: Context = graphicOverlay.context

    /**
     * Opens the camera and starts sending preview frames to the underlying detector. The supplied
     * surface holder is used for the preview so frames can be displayed to the user.
     *
     * @param surfaceHolder the surface holder to use for the preview frames.
     * @throws IOException if the supplied surface holder could not be used as the preview display.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun start(surfaceHolder: SurfaceHolder) {
        if (camera != null) return

        camera = createCamera().apply {
            setPreviewDisplay(surfaceHolder)
            startPreview()
        }

        processingThread = Thread(processingRunnable).apply {
            processingRunnable.setActive(true)
            start()
        }
    }

    /**
     * Closes the camera and stops sending frames to the underlying frame detector.
     *
     *
     * This camera source may be restarted again by calling [.start].
     *
     *
     * Call [.release] instead to completely shut down this camera source and release the
     * resources of the underlying detector.
     */
    @Synchronized
    internal fun stop() {
        processingRunnable.setActive(false)
        processingThread?.let {
            try {
                // Waits for the thread to complete to ensure that we can't have multiple threads executing
                // at the same time (i.e., which would happen if we called start too quickly after stop).
                it.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Frame processing thread interrupted on stop.")
            }
            processingThread = null
        }

        camera?.let {
            it.stopPreview()
            it.setPreviewCallbackWithBuffer(null)
            try {
                it.setPreviewDisplay(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear camera preview: $e")
            }
            it.release()
            camera = null
        }

        // Release the reference to any image buffers, since these will no longer be in use.
        bytesToByteBuffer.clear()
    }

    /** Stops the camera and releases the resources of the camera and underlying detector.  */
    fun release() {
        graphicOverlay.clear()
        synchronized(processorLock) {
            stop()
            frameProcessor?.stop()
        }
    }

    fun setFrameProcessor(processor: FrameProcessor) {
        graphicOverlay.clear()
        synchronized(processorLock) {
            frameProcessor?.stop()
            frameProcessor = processor
        }
    }

    fun updateFlashMode(flashMode: String) {
        val parameters = camera?.parameters
        parameters?.flashMode = flashMode
        camera?.parameters = parameters
    }

    /**
     * Opens the camera and applies the user settings.
     *
     * @throws IOException if camera cannot be found or preview cannot be processed.
     */
    @Throws(IOException::class)
    private fun createCamera(): Camera {
        val camera = Camera.open() ?: throw IOException("There is no back-facing camera.")
        val parameters = camera.parameters
        setPreviewAndPictureSize(camera, parameters)
        setRotation(camera, parameters)

        val previewFpsRange = selectPreviewFpsRange(camera)
            ?: throw IOException("Could not find suitable preview frames per second range.")
        parameters.setPreviewFpsRange(
            previewFpsRange[Parameters.PREVIEW_FPS_MIN_INDEX],
            previewFpsRange[Parameters.PREVIEW_FPS_MAX_INDEX]
        )

        parameters.previewFormat = IMAGE_FORMAT

        if (parameters.supportedFocusModes.contains(Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.focusMode = Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        } else {
            Log.i(TAG, "Camera auto focus is not supported on this device.")
        }

        camera.parameters = parameters

        camera.setPreviewCallbackWithBuffer(processingRunnable::setNextFrame)

        // Four frame buffers are needed for working with the camera:
        //
        //   one for the frame that is currently being executed upon in doing detection
        //   one for the next pending frame to process immediately upon completing detection
        //   two for the frames that the camera uses to populate future preview images
        //
        // Through trial and error it appears that two free buffers, in addition to the two buffers
        // used in this code, are needed for the camera to work properly. Perhaps the camera has one
        // thread for acquiring images, and another thread for calling into user code. If only three
        // buffers are used, then the camera will spew thousands of warning messages when detection
        // takes a non-trivial amount of time.
        previewSize?.let {
            camera.addCallbackBuffer(createPreviewBuffer(it))
            camera.addCallbackBuffer(createPreviewBuffer(it))
            camera.addCallbackBuffer(createPreviewBuffer(it))
            camera.addCallbackBuffer(createPreviewBuffer(it))
        }

        return camera
    }

    @Throws(IOException::class)
    private fun setPreviewAndPictureSize(camera: Camera, parameters: Parameters) {

        // Gives priority to the preview size specified by the user if exists.
        val sizePair: CameraSizePair = PreferenceUtils.getUserSpecifiedPreviewSize(context) ?: run {
            // Camera preview size is based on the landscape mode, so we need to also use the aspect
            // ration of display in the same mode for comparison.
            val displayAspectRatioInLandscape: Float =
                if (CameraUtils.isPortraitMode(graphicOverlay.context)) {
                    graphicOverlay.height.toFloat() / graphicOverlay.width
                } else {
                    graphicOverlay.width.toFloat() / graphicOverlay.height
                }
            selectSizePair(camera, displayAspectRatioInLandscape)
        } ?: throw IOException("Could not find suitable preview size.")

        previewSize = sizePair.preview.also {
            Log.v(TAG, "Camera preview size: $it")
            parameters.setPreviewSize(it.width, it.height)
        }

        sizePair.picture?.let { pictureSize ->
            Log.v(TAG, "Camera picture size: $pictureSize")
            parameters.setPictureSize(pictureSize.width, pictureSize.height)
            PreferenceUtils.saveStringPreference(
                context, R.string.pref_key_rear_camera_picture_size, pictureSize.toString()
            )
        }
    }

    /**
     * Calculates the correct rotation for the given camera id and sets the rotation in the
     * parameters. It also sets the camera's display orientation and rotation.
     *
     * @param parameters the camera parameters for which to set the rotation.
     */
    private fun setRotation(camera: Camera, parameters: Parameters) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val degrees = when (val deviceRotation = windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> {
                Log.e(TAG, "Bad device rotation value: $deviceRotation")
                0
            }
        }

        val cameraInfo = CameraInfo()
        Camera.getCameraInfo(CAMERA_FACING_BACK, cameraInfo)
        val angle = (cameraInfo.orientation - degrees + 360) % 360
        this.rotationDegrees = angle
        camera.setDisplayOrientation(angle)
        parameters.setRotation(angle)
    }

    /**
     * Creates one buffer for the camera preview callback. The size of the buffer is based off of the
     * camera preview size and the format of the camera image.
     *
     * @return a new preview buffer of the appropriate size for the current camera settings.
     */
    private fun createPreviewBuffer(previewSize: Size): ByteArray {
        val bitsPerPixel = ImageFormat.getBitsPerPixel(IMAGE_FORMAT)
        val sizeInBits = previewSize.height.toLong() * previewSize.width.toLong() * bitsPerPixel.toLong()
        val bufferSize = ceil(sizeInBits / 8.0).toInt() + 1

        // Creating the byte array this way and wrapping it, as opposed to using .allocate(),
        // should guarantee that there will be an array to work with.
        val byteArray = ByteArray(bufferSize)
        val byteBuffer = ByteBuffer.wrap(byteArray)
        check(!(!byteBuffer.hasArray() || !byteBuffer.array()!!.contentEquals(byteArray))) {
            // This should never happen. If it does, then we wouldn't be passing the preview content to
            // the underlying detector later.
            "Failed to create valid buffer for camera source."
        }

        bytesToByteBuffer[byteArray] = byteBuffer
        return byteArray
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera. This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     *
     *
     * While detection is running on a frame, new frames may be received from the camera. As these
     * frames come in, the most recent frame is held onto as pending. As soon as detection and its
     * associated processing is done for the previous frame, detection on the mostly recently received
     * frame will immediately start on the same thread.
     */
    private inner class FrameProcessingRunnable internal constructor() : Runnable {

        // This lock guards all of the member variables below.
        private val lock = Object()
        private var active = true

        // These pending variables hold the state associated with the new frame awaiting processing.
        private var pendingFrameData: ByteBuffer? = null

        /** Marks the runnable as active/not active. Signals any blocked threads to continue.  */
        internal fun setActive(active: Boolean) {
            synchronized(lock) {
                this.active = active
                lock.notifyAll()
            }
        }

        /**
         * Sets the frame data received from the camera. This adds the previous unused frame buffer (if
         * present) back to the camera, and keeps a pending reference to the frame data for future use.
         */
        internal fun setNextFrame(data: ByteArray, camera: Camera) {
            synchronized(lock) {
                pendingFrameData?.let {
                    camera.addCallbackBuffer(it.array())
                    pendingFrameData = null
                }

                if (!bytesToByteBuffer.containsKey(data)) {
                    Log.d(
                        TAG,
                        "Skipping frame. Could not find ByteBuffer associated with the image data from the camera."
                    )
                    return
                }

                pendingFrameData = bytesToByteBuffer[data]

                // Notify the processor thread if it is waiting on the next frame (see below).
                lock.notifyAll()
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames continuously.
         * The next pending frame is either immediately available or hasn't been received yet. Once it
         * is available, we transfer the frame info to local variables and run detection on that frame.
         * It immediately loops back for the next frame without pausing.
         *
         *
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context switching
         * or frame acquisition time latency.
         *
         *
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        override fun run() {
            var data: ByteBuffer?

            while (true) {
                synchronized(lock) {
                    while (active && pendingFrameData == null) {
                        try {
                            // Wait for the next frame to be received from the camera, since we don't have it yet.
                            lock.wait()
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "Frame processing loop terminated.", e)
                            return
                        }
                    }

                    if (!active) {
                        // Exit the loop once this camera source is stopped or released.  We check this here,
                        // immediately after the wait() above, to handle the case where setActive(false) had
                        // been called, triggering the termination of this loop.
                        return
                    }

                    // Hold onto the frame data locally, so that we can use this for detection
                    // below.  We need to clear pendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    data = pendingFrameData
                    pendingFrameData = null
                }

                try {
                    synchronized(processorLock) {
                        val frameMetadata = FrameMetadata(previewSize!!.width, previewSize!!.height, rotationDegrees)
                        data?.let {
                            frameProcessor?.process(it, frameMetadata, graphicOverlay)
                        }
                    }
                } catch (t: Exception) {
                    Log.e(TAG, "Exception thrown from receiver.", t)
                } finally {
                    data?.let {
                        camera?.addCallbackBuffer(it.array())
                    }
                }
            }
        }
    }

    companion object {

        const val CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK

        private const val TAG = "CameraSource"

        private const val IMAGE_FORMAT = ImageFormat.NV21
        private const val MIN_CAMERA_PREVIEW_WIDTH = 400
        private const val MAX_CAMERA_PREVIEW_WIDTH = 1300
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH = 640
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT = 360
        private const val REQUESTED_CAMERA_FPS = 30.0f

        /**
         * Selects the most suitable preview and picture size, given the display aspect ratio in landscape
         * mode.
         *
         *
         * It's firstly trying to pick the one that has closest aspect ratio to display view with its
         * width be in the specified range [[.MIN_CAMERA_PREVIEW_WIDTH], [ ][.MAX_CAMERA_PREVIEW_WIDTH]]. If there're multiple candidates, choose the one having longest
         * width.
         *
         *
         * If the above looking up failed, chooses the one that has the minimum sum of the differences
         * between the desired values and the actual values for width and height.
         *
         *
         * Even though we only need to find the preview size, it's necessary to find both the preview
         * size and the picture size of the camera together, because these need to have the same aspect
         * ratio. On some hardware, if you would only set the preview size, you will get a distorted
         * image.
         *
         * @param camera the camera to select a preview size from
         * @return the selected preview and picture size pair
         */
        private fun selectSizePair(camera: Camera, displayAspectRatioInLandscape: Float): CameraSizePair? {
            val validPreviewSizes = CameraUtils.generateValidPreviewSizeList(camera)

            var selectedPair: CameraSizePair? = null
            // Picks the preview size that has closest aspect ratio to display view.
            var minAspectRatioDiff = Float.MAX_VALUE

            for (sizePair in validPreviewSizes) {
                val previewSize = sizePair.preview
                if (previewSize.width < MIN_CAMERA_PREVIEW_WIDTH || previewSize.width > MAX_CAMERA_PREVIEW_WIDTH) {
                    continue
                }

                val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
                val aspectRatioDiff = abs(displayAspectRatioInLandscape - previewAspectRatio)
                if (abs(aspectRatioDiff - minAspectRatioDiff) < CameraUtils.ASPECT_RATIO_TOLERANCE) {
                    if (selectedPair == null || selectedPair.preview.width < sizePair.preview.width) {
                        selectedPair = sizePair
                    }
                } else if (aspectRatioDiff < minAspectRatioDiff) {
                    minAspectRatioDiff = aspectRatioDiff
                    selectedPair = sizePair
                }
            }

            if (selectedPair == null) {
                // Picks the one that has the minimum sum of the differences between the desired values and
                // the actual values for width and height.
                var minDiff = Integer.MAX_VALUE
                for (sizePair in validPreviewSizes) {
                    val size = sizePair.preview
                    val diff =
                        abs(size.width - DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH) +
                                abs(size.height - DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT)
                    if (diff < minDiff) {
                        selectedPair = sizePair
                        minDiff = diff
                    }
                }
            }

            return selectedPair
        }

        /**
         * Selects the most suitable preview frames per second range.
         *
         * @param camera the camera to select a frames per second range from
         * @return the selected preview frames per second range
         */
        private fun selectPreviewFpsRange(camera: Camera): IntArray? {
            // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
            // rates.
            val desiredPreviewFpsScaled = (REQUESTED_CAMERA_FPS * 1000f).toInt()

            // The method for selecting the best range is to minimize the sum of the differences between
            // the desired value and the upper and lower bounds of the range.  This may select a range
            // that the desired value is outside of, but this is often preferred.  For example, if the
            // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
            // range (15, 30).
            var selectedFpsRange: IntArray? = null
            var minDiff = Integer.MAX_VALUE
            for (range in camera.parameters.supportedPreviewFpsRange) {
                val deltaMin = desiredPreviewFpsScaled - range[Parameters.PREVIEW_FPS_MIN_INDEX]
                val deltaMax = desiredPreviewFpsScaled - range[Parameters.PREVIEW_FPS_MAX_INDEX]
                val diff = abs(deltaMin) + abs(deltaMax)
                if (diff < minDiff) {
                    selectedFpsRange = range
                    minDiff = diff
                }
            }
            return selectedFpsRange
        }
    }
}
