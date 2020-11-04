package com.csi.arsample

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.csi.arsample.databinding.ActivityMainBinding
import com.csi.arsample.ext.checkIsSupportedDevice
import com.csi.arsample.helper.*
import com.csi.arsample.renderer.*
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.vanniktech.rxpermission.Permission
import com.vanniktech.rxpermission.RealRxPermission
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import java.io.IOException
import java.util.*


class MainActivity : AppCompatActivity(), SampleRender.Renderer {
    private lateinit var viewBinding: ActivityMainBinding

    private var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private lateinit var tapHelper: TapHelper
    private val depthSettings: DepthSettings = DepthSettings()
    private val instantPlacementSettings: InstantPlacementSettings = InstantPlacementSettings()
    private val instantPlacementSettingsMenuDialogCheckboxes = BooleanArray(1)

    companion object {
        private val TAG: String =
            MainActivity::class.java.simpleName
        private const val AMBIENT_INTENSITY_VERTEX_SHADER_NAME = "shaders/ambient_intensity.vert"
        private const val AMBIENT_INTENSITY_FRAGMENT_SHADER_NAME = "shaders/ambient_intensity.frag"
        private const val SEARCHING_PLANE_MESSAGE = "Searching for surfaces..."
        const val APPROXIMATE_DISTANCE_METERS = 2.0f
        private const val POINT_CLOUD_VERTEX_SHADER_NAME = "shaders/point_cloud.vert"
        private const val POINT_CLOUD_FRAGMENT_SHADER_NAME = "shaders/point_cloud.frag"
    }

    private var installRequested = false

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.

    private var depthTexture: Texture? = null
    private var calculateUVTransform = true
    private var planeRenderer: PlaneRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var hasSetTextureNames = false

    private val depthSettingsMenuDialogCheckboxes = BooleanArray(2)

    // Point Cloud
    private var pointCloudVertexBuffer: VertexBuffer? = null
    private var pointCloudMesh: Mesh? = null
    private var pointCloudShader: Shader? = null

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    private var lastPointCloudTimestamp: Long = 0

    // Note: the last component must be zero to avoid applying the translational part of the matrix.
    private val LIGHT_DIRECTION = floatArrayOf(0.250f, 0.866f, 0.433f, 0.0f)
    private var virtualObjectMesh: Mesh? = null
    private var virtualObjectShader: Shader? = null
    private var virtualObjectDepthShader: Shader? = null

    // Anchors created from taps used for object placing with a given color.
    private class ColoredAnchor(val anchor: Anchor, var color: FloatArray, val trackable: Trackable)

    private val anchors = ArrayList<ColoredAnchor>()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model

    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    private val viewLightDirection = FloatArray(4) // view x LIGHT_DIRECTION
    private var render: SampleRender? = null


    private val compositeDisposable : CompositeDisposable = CompositeDisposable()
    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkIsSupportedDevice()) {
            finish()
            return
        }
        tapHelper = TapHelper(this)

        viewBinding = ActivityMainBinding.inflate(LayoutInflater.from(this))
        setContentView(viewBinding.root)
        val permission: String = android.Manifest.permission.CAMERA
        RealRxPermission.getInstance(this)
            .request(permission)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy({
            }) {
                if (it.state() == Permission.State.DENIED_NOT_SHOWN) {
                    finish()
                }
            }.addTo(compositeDisposable)

        // Set up renderer.

        // Set up renderer.
        render = SampleRender(viewBinding.surfaceView, this, assets)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)
        // Must be done during an initialization phase like onCreate

        installRequested = false
        calculateUVTransform =true
        viewBinding.surfaceView.setOnTouchListener(tapHelper)
        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)

    }


    override fun onResume() {
        super.onResume()
        if(session == null) {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                InstallStatus.INSTALLED -> {
                }
            }
            session = Session(this)
        }


        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession()
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            return
        }
        viewBinding.surfaceView.onResume()
        displayRotationHelper?.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            displayRotationHelper?.onPause()
            viewBinding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    /** Configures the session with feature settings.  */
    private fun configureSession() {
        val config = session!!.config
        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        } else {
            config.depthMode = Config.DepthMode.DISABLED
        }
        if (instantPlacementSettings.isInstantPlacementEnabled()) {
            config.instantPlacementMode = InstantPlacementMode.LOCAL_Y_UP
        } else {
            config.instantPlacementMode = InstantPlacementMode.DISABLED
        }
        session!!.configure(config)
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.dispose()
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            depthTexture =
                Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE)
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render, depthTexture)
            // Point cloud
            pointCloudShader = Shader.createFromAssets(
                render,
                POINT_CLOUD_VERTEX_SHADER_NAME,
                POINT_CLOUD_FRAGMENT_SHADER_NAME,  /*defines=*/
                null
            )
                .set4(
                    "u_Color",
                    floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
                )
                .set1("u_PointSize", 5.0f)
            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer = VertexBuffer(
                render,  /*numberOfEntriesPerVertex=*/
                4,  /*entries=*/
                null
            )
            val pointCloudVertexBuffers = arrayOf<VertexBuffer>(pointCloudVertexBuffer!!)
            pointCloudMesh = Mesh(
                render, Mesh.PrimitiveMode.POINTS,  /*indexBuffer=*/null, pointCloudVertexBuffers
            )

            // Virtual object to render (Andy the android)
            val virtualObjectTexture =
                Texture.createFromAsset(render, "models/andy.png", Texture.WrapMode.CLAMP_TO_EDGE)
            virtualObjectMesh = Mesh.createFromAsset(render, "models/andy.obj")
            virtualObjectShader = createVirtualObjectShader(
                render, virtualObjectTexture,  /*use_depth_for_occlusion=*/false
            )
            virtualObjectDepthShader = createVirtualObjectShader(
                render,
                virtualObjectTexture,  /*use_depth_for_occlusion=*/
                true
            )
                .setTexture("u_DepthTexture", depthTexture)
        } catch (e: IOException) {
            Log.e(
                TAG,
                "Failed to read an asset file",
                e
            )
        }
    }

    override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        if (session == null) {
            return
        }
        if (!hasSetTextureNames) {
            session!!.setCameraTextureNames(intArrayOf(backgroundRenderer!!.textureId))
            hasSetTextureNames = true
        }

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session!!)
        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera
            if (frame.hasDisplayGeometryChanged() || calculateUVTransform) {
                // The UV Transform represents the transformation between screenspace in normalized units
                // and screenspace in units of pixels.  Having the size of each pixel is necessary in the
                // virtual object shader, to perform kernel-based blur effects.
                calculateUVTransform = false
                val transform = getTextureTransformMatrix(frame)
                virtualObjectDepthShader!!.setMatrix3("u_DepthUvTransform", transform)
            }
            if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                // The rendering abstraction leaks a bit here. Populate the depth texture with the current
                // frame data.
                try {
                    frame.acquireDepthImage().also { depthImage ->
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture!!.textureId)
                        GLES30.glTexImage2D(
                            GLES30.GL_TEXTURE_2D,
                            0,
                            GLES30.GL_RG8,
                            depthImage.width,
                            depthImage.height,
                            0,
                            GLES30.GL_RG,
                            GLES30.GL_UNSIGNED_BYTE,
                            depthImage.planes[0].buffer
                        )
                        val aspectRatio =
                            depthImage.width.toFloat() / depthImage.height
                                .toFloat()
                        virtualObjectDepthShader!!.set1("u_DepthAspectRatio", aspectRatio)
                    }
                } catch (e: NotYetAvailableException) {
                    // This normally means that depth data is not available yet. This is normal so we will not
                    // spam the logcat with this.
                }
            }

            // Handle one tap per frame.
            handleTap(frame, camera)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer!!.draw(render, frame, depthSettings.depthColorVisualizationEnabled())

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3D objects, show tracking failure reason instead.
            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }

            // Get projection matrix.
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            camera.getViewMatrix(viewMatrix, 0)

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            val colorCorrectionRgba = FloatArray(4)
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
            frame.acquirePointCloud().use { pointCloud ->
                if (pointCloud.timestamp > lastPointCloudTimestamp) {
                    pointCloudVertexBuffer!!.set(pointCloud.points)
                    lastPointCloudTimestamp = pointCloud.timestamp
                }
                Matrix.multiplyMM(
                    modelViewProjectionMatrix,
                    0,
                    projectionMatrix,
                    0,
                    viewMatrix,
                    0
                )
                pointCloudShader!!.setMatrix4("u_ModelViewProjection", modelViewProjectionMatrix)
                render.draw(pointCloudMesh, pointCloudShader)
            }

            // No tracking error at this point. If we detected any plane, then hide the
            // message UI, otherwise show searchingPlane message.

            // Visualize planes.
            planeRenderer!!.drawPlanes(
                render,
                session!!.getAllTrackables(Plane::class.java),
                camera.displayOrientedPose,
                projectionMatrix
            )

            // Visualize anchors created by touch.
            for (coloredAnchor in anchors) {
                if (coloredAnchor.anchor.trackingState != TrackingState.TRACKING) {
                    continue
                }

                // For anchors attached to Instant Placement points, update the color once the tracking
                // method becomes FULL_TRACKING.
                if (coloredAnchor.trackable is InstantPlacementPoint
                    && coloredAnchor.trackable.trackingMethod
                    == InstantPlacementPoint.TrackingMethod.FULL_TRACKING
                ) {
                    coloredAnchor.color = getTrackableColor(coloredAnchor.trackable)
                }

                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to session.update() as ARCore refines its estimate of the world.
                coloredAnchor.anchor.pose.toMatrix(modelMatrix, 0)

                // Calculate model/view/projection matrices and view-space light direction
                Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                Matrix.multiplyMM(
                    modelViewProjectionMatrix,
                    0,
                    projectionMatrix,
                    0,
                    modelViewMatrix,
                    0
                )
                Matrix.multiplyMV(
                    viewLightDirection,
                    0,
                    viewMatrix,
                    0,
                    LIGHT_DIRECTION,
                    0
                )

                // Update shader properties and draw
                val shader =
                    if (depthSettings.useDepthForOcclusion()) virtualObjectDepthShader!! else virtualObjectShader!!
                shader
                    .setMatrix4("u_ModelView", modelViewMatrix)
                    .setMatrix4("u_ModelViewProjection", modelViewProjectionMatrix)
                    .set4("u_ColorCorrection", colorCorrectionRgba)
                    .set4("u_ViewLightDirection", viewLightDirection)
                    .set3("u_AlbedoColor", coloredAnchor.color)
                render.draw(virtualObjectMesh, shader)
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(
                MainActivity::class.java.simpleName,
                "Exception on the OpenGL thread",
                t
            )
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            val hitResultList: List<HitResult>
            hitResultList = if (instantPlacementSettings.isInstantPlacementEnabled()) {
                frame.hitTestInstantPlacement(
                    tap.x,
                    tap.y,
                    APPROXIMATE_DISTANCE_METERS
                )
            } else {
                frame.hitTest(tap)
            }
            for (hit in hitResultList) {
                // If any plane, Oriented Point, or Instant Placement Point was hit, create an anchor.
                val trackable = hit.trackable
                // If a plane was hit, check that it was hit inside the plane polygon.
                if ((trackable is Plane
                            && trackable.isPoseInPolygon(hit.hitPose)
                            && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                    || (trackable is Point
                            && trackable.orientationMode
                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                    || trackable is InstantPlacementPoint
                ) {
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size >= 20) {
                        anchors[0].anchor.detach()
                        anchors.removeAt(0)
                    }
                    val objColor = getTrackableColor(trackable)

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    anchors.add(ColoredAnchor(hit.createAnchor(), objColor, trackable))
                    // For devices that support the Depth API, shows a dialog to suggest enabling
                    // depth-based occlusion. This dialog needs to be spawned on the UI thread.
                    runOnUiThread { showOcclusionDialogIfNeeded() }

                    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, or
                    // Instant Placement Point.
                    break
                }
            }
        }
    }

    /**
     * Shows a pop-up dialog on the first call, determining whether the user wants to enable
     * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
     */
    private fun showOcclusionDialogIfNeeded() {
        val isDepthSupported = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
            return  // Don't need to show dialog.
        }

        // Asks the user whether they want to use depth-based occlusion.
        AlertDialog.Builder(this)
            .setTitle(R.string.options_title_with_depth)
            .setMessage(R.string.depth_use_explanation)
            .setPositiveButton(
                R.string.button_text_enable_depth
            ) { dialog: DialogInterface?, which: Int -> depthSettings.setUseDepthForOcclusion(true) }
            .setNegativeButton(
                R.string.button_text_disable_depth
            ) { dialog: DialogInterface?, which: Int -> depthSettings.setUseDepthForOcclusion(false) }
            .show()
    }

    private fun launchInstantPlacementSettingsMenuDialog() {
        resetSettingsMenuDialogCheckboxes()
        val resources = resources
        AlertDialog.Builder(this)
            .setTitle(R.string.options_title_instant_placement)
            .setMultiChoiceItems(
                resources.getStringArray(R.array.instant_placement_options_array),
                instantPlacementSettingsMenuDialogCheckboxes,
                OnMultiChoiceClickListener { dialog: DialogInterface?, which: Int, isChecked: Boolean ->
                    instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked
                })
            .setPositiveButton(
                R.string.done
            ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
            .setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> resetSettingsMenuDialogCheckboxes() })
            .show()
    }

    /** Shows checkboxes to the user to facilitate toggling of depth-based effects.  */
    private fun launchDepthSettingsMenuDialog() {
        // Retrieves the current settings to show in the checkboxes.
        resetSettingsMenuDialogCheckboxes()

        // Shows the dialog to the user.
        val resources = resources
        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            // With depth support, the user can select visualization options.
            AlertDialog.Builder(this)
                .setTitle(R.string.options_title_with_depth)
                .setMultiChoiceItems(
                    resources.getStringArray(R.array.depth_options_array),
                    depthSettingsMenuDialogCheckboxes,
                    OnMultiChoiceClickListener { dialog: DialogInterface?, which: Int, isChecked: Boolean ->
                        depthSettingsMenuDialogCheckboxes[which] = isChecked
                    })
                .setPositiveButton(
                    R.string.done
                ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
                .setNegativeButton(
                    android.R.string.cancel,
                    DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> resetSettingsMenuDialogCheckboxes() })
                .show()
        } else {
            // Without depth support, no settings are available.
            AlertDialog.Builder(this)
                .setTitle(R.string.options_title_without_depth)
                .setPositiveButton(
                    R.string.done
                ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
                .show()
        }
    }

    private fun applySettingsMenuDialogCheckboxes() {
        depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0])
        depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1])
        instantPlacementSettings.setInstantPlacementEnabled(
            instantPlacementSettingsMenuDialogCheckboxes[0]
        )
        configureSession()
    }

    private fun resetSettingsMenuDialogCheckboxes() {
        depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion()
        depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled()
        instantPlacementSettingsMenuDialogCheckboxes[0] =
            instantPlacementSettings.isInstantPlacementEnabled()
    }

    /** Checks if we detected at least one plane.  */
    private fun hasTrackingPlane(): Boolean {
        for (plane in session!!.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    /**
     * Returns a transformation matrix that when applied to screen space uvs makes them match
     * correctly with the quad texture coords used to render the camera feed. It takes into account
     * device orientation.
     */
    private fun getTextureTransformMatrix(frame: Frame): FloatArray {
        val frameTransform = FloatArray(6)
        val uvTransform = FloatArray(9)
        // XY pairs of coordinates in NDC space that constitute the origin and points along the two
        // principal axes.
        val ndcBasis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

        // Temporarily store the transformed points into outputTransform.
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcBasis,
            Coordinates2d.TEXTURE_NORMALIZED,
            frameTransform
        )

        // Convert the transformed points into an affine transform and transpose it.
        val ndcOriginX = frameTransform[0]
        val ndcOriginY = frameTransform[1]
        uvTransform[0] = frameTransform[2] - ndcOriginX
        uvTransform[1] = frameTransform[3] - ndcOriginY
        uvTransform[2] = 0F
        uvTransform[3] = frameTransform[4] - ndcOriginX
        uvTransform[4] = frameTransform[5] - ndcOriginY
        uvTransform[5] = 0F
        uvTransform[6] = ndcOriginX
        uvTransform[7] = ndcOriginY
        uvTransform[8] = 1F
        return uvTransform
    }

    @Throws(IOException::class)
    private fun createVirtualObjectShader(
        render: SampleRender, virtualObjectTexture: Texture, useDepthForOcclusion: Boolean
    ): Shader {
        return Shader.createFromAssets(
            render,
            AMBIENT_INTENSITY_VERTEX_SHADER_NAME,
            AMBIENT_INTENSITY_FRAGMENT_SHADER_NAME,
            object : HashMap<String?, String?>() {
                init {
                    put("USE_DEPTH_FOR_OCCLUSION", if (useDepthForOcclusion) "1" else "0")
                }
            })
            .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA)
            .setTexture("u_AlbedoTexture", virtualObjectTexture)
            .set1("u_UpperDiffuseIntensity", 1.0f)
            .set1("u_LowerDiffuseIntensity", 0.5f)
            .set1("u_SpecularIntensity", 0.2f)
            .set1("u_SpecularPower", 8.0f)
    }
    /**
     * Assign a color to the object for rendering based on the trackable type this anchor attached to.
     * For AR_TRACKABLE_POINT, it's blue color.
     * For AR_TRACKABLE_PLANE, it's green color.
     * For AR_TRACKABLE_INSTANT_PLACEMENT_POINT while tracking method is
     * SCREENSPACE_WITH_APPROXIMATE_DISTANCE, it's white color.
     * For AR_TRACKABLE_INSTANT_PLACEMENT_POINT once tracking method becomes FULL_TRACKING, it's
     * orange color.
     * The color will update for an InstantPlacementPoint once it updates its tracking method from
     * SCREENSPACE_WITH_APPROXIMATE_DISTANCE to FULL_TRACKING.
     */
    private fun getTrackableColor(trackable: Trackable): FloatArray {
        if (trackable is Point) {
            return floatArrayOf(66.0f / 255.0f, 133.0f / 255.0f, 244.0f / 255.0f)
        }
        if (trackable is Plane) {
            return floatArrayOf(139.0f / 255.0f, 195.0f / 255.0f, 74.0f / 255.0f)
        }
        if (trackable is InstantPlacementPoint) {
            if (trackable.trackingMethod
                == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
            ) {
                return floatArrayOf(255.0f / 255.0f, 255.0f / 255.0f, 255.0f / 255.0f)
            }
            if (trackable.trackingMethod == InstantPlacementPoint.TrackingMethod.FULL_TRACKING) {
                return floatArrayOf(255.0f / 255.0f, 167.0f / 255.0f, 38.0f / 255.0f)
            }
        }
        // Fallback color.
        return floatArrayOf(0f, 0f, 0f)
    }
}