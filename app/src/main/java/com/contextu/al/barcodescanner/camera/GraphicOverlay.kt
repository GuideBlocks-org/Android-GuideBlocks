package com.contextu.al.barcodescanner.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.contextu.al.utils.CameraUtils
import java.util.ArrayList

/**
 * A view which renders a series of custom graphics to be overlaid on top of an associated preview
 * (i.e., the camera preview). The creator can add graphics objects, update the objects, and remove
 * them, triggering the appropriate drawing and invalidation within the view.
 *
 *
 * Supports scaling and mirroring of the graphics relative the camera's preview properties. The
 * idea is that detection items are expressed in terms of a preview size, but need to be scaled up
 * to the full view size, and also mirrored in the case of the front-facing camera.
 *
 *
 * Associated [Graphic] items should use [.translateX] and [ ][.translateY] to convert to view coordinate from the preview's coordinate.
 */
class GraphicOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val lock = Any()

    private var previewWidth: Int = 0
    private var widthScaleFactor = 1.0f
    private var previewHeight: Int = 0
    private var heightScaleFactor = 1.0f
    private val graphics = ArrayList<Graphic>()

    /**
     * Base class for a custom graphics object to be rendered within the graphic overlay. Subclass
     * this and implement the [Graphic.draw] method to define the graphics element. Add
     * instances to the overlay using [GraphicOverlay.add].
     */
    abstract class Graphic protected constructor(val overlay: GraphicOverlay) {
        protected val context: Context = overlay.context

        /** Draws the graphic on the supplied canvas.  */
        abstract fun draw(canvas: Canvas)
    }

    /** Removes all graphics from the overlay.  */
    fun clear() {
        synchronized(lock) {
            graphics.clear()
        }
        postInvalidate()
    }

    fun add(graphic: Graphic) {
        synchronized(lock) {
            graphics.add(graphic)
        }
    }

    fun setCameraInfo(cameraSource: CameraSource) {
        val previewSize = cameraSource.previewSize ?: return
        if (CameraUtils.isPortraitMode(context)) {
            // Swap width and height when in portrait, since camera's natural orientation is landscape.
            previewWidth = previewSize.height
            previewHeight = previewSize.width
        } else {
            previewWidth = previewSize.width
            previewHeight = previewSize.height
        }
    }

    fun translateX(x: Float): Float = x * widthScaleFactor
    fun translateY(y: Float): Float = y * heightScaleFactor

    fun translateRect(rect: Rect) = RectF(
        translateX(rect.left.toFloat()),
        translateY(rect.top.toFloat()),
        translateX(rect.right.toFloat()),
        translateY(rect.bottom.toFloat())
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (previewWidth > 0 && previewHeight > 0) {
            widthScaleFactor = width.toFloat() / previewWidth
            heightScaleFactor = height.toFloat() / previewHeight
        }

        synchronized(lock) {
            graphics.forEach { it.draw(canvas) }
        }
    }
}