package expo.modules.lutfilter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsic3DLUT
import android.renderscript.Type
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.exception.CodedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID

class ExpoLutFilterModule : Module() {
  // Properties for grain overlay
  private var grainOpacity: Double = 0.8
  private var grainBitmap: Bitmap? = null
  private var grainBlendMode: String = "screen" // Android equivalent of CIScreenBlendMode
  
  // Cache for parsed LUT filters to improve performance
  private val filterCache = mutableMapOf<String, LutFilter>()
  
  // RenderScript instance for 3D LUT processing
  private val renderScript: RenderScript by lazy {
    RenderScript.create(appContext.reactContext)
  }
  
  // Custom exception classes matching iOS
  class InputError(message: String) : CodedException(message)
  
  override fun definition() = ModuleDefinition {
    Name("ExpoLutFilter")
    
    // Grain overlay functions
    AsyncFunction("setGrainImage") { grainUri: String ->
      try {
        grainBitmap = loadBitmapFromUri(grainUri)
        if (grainBitmap == null) {
          throw InputError("Failed to load grain image")
        }
      } catch (e: Exception) {
        throw InputError("Failed to load grain image: ${e.message}")
      }
    }
    
    Function("setGrainOpacity") { opacity: Double ->
      grainOpacity = opacity.coerceIn(0.0, 1.0)
    }
    
    Function("setGrainBlendMode") { blendMode: String ->
      grainBlendMode = blendMode
    }
    
    // Main LUT processing function
    AsyncFunction("applyLUT") {
      inputImageUri: String,
      filterId: String,
      lutUri: String,
      lutDimension: Int,
      intensity: Double,
      withGrain: Boolean,
      metadata: String ->

      try {
        println("📷 [ANDROID] applyLUT called with:")
        println("📷 [ANDROID]   inputImageUri: '$inputImageUri' (${inputImageUri?.javaClass?.simpleName})")
        println("📷 [ANDROID]   filterId: $filterId")
        println("📷 [ANDROID]   lutUri: $lutUri")
        println("📷 [ANDROID]   lutDimension: $lutDimension")
        println("📷 [ANDROID]   intensity: $intensity")
        println("📷 [ANDROID]   metadata: $metadata")

        // Load input image
        val inputBitmap = loadBitmapFromUri(inputImageUri)
          ?: throw InputError("Failed to load input image")

        println("📷 [ANDROID] Input image loaded successfully")

        // Get or create LUT filter
        val lutFilter = filterCache.getOrPut(filterId) {
          println("📷 [ANDROID] Loading LUT filter for $filterId")
          val lutBitmap = loadBitmapFromUri(lutUri)
            ?: throw InputError("Failed to load LUT image")
          LutFilter(filterId, lutBitmap, lutDimension)
        }

        // Set filter intensity
        lutFilter.intensity = intensity

        println("📷 [ANDROID] LUT filter ready, applying transformation with intensity $intensity")

        // Apply LUT transformation
        var processedBitmap = applyLutToBitmap(inputBitmap, lutFilter)

        // Apply grain overlay if requested
        if (withGrain && grainBitmap != null) {
          println("Applying grain...")
          processedBitmap = applyGrainOverlay(processedBitmap, grainBitmap!!, grainOpacity, grainBlendMode)
          println("Applied grain!")
        }

        // Save to cache with high quality (0.95) and metadata
        val outputUri = saveBitmapToCache(processedBitmap, metadata, 0.95)
        return@AsyncFunction outputUri

      } catch (e: InputError) {
        throw e
      } catch (e: Exception) {
        throw InputError("Failed to apply LUT: ${e.message}")
      }
    }
  }
  
  /**
   * Loads a bitmap from a URI (supports file:// and content:// URIs)
   * Properly handles EXIF orientation to match iOS behavior
   */
  private fun loadBitmapFromUri(uriString: String): Bitmap? {
    return try {
      println("📷 [ANDROID] Attempting to load bitmap from: $uriString")

      // Get the file path for EXIF reading
      val filePath = when {
        uriString.startsWith("/") -> uriString
        uriString.startsWith("file://") -> Uri.parse(uriString).path
        else -> null
      }

      // Handle both URI formats and direct file paths
      val inputStream = when {
        // Direct file path (no scheme)
        uriString.startsWith("/") -> {
          println("📷 [ANDROID] Loading from direct file path")
          val file = File(uriString)
          println("📷 [ANDROID] File exists: ${file.exists()}, readable: ${file.canRead()}")
          file.inputStream()
        }
        // URI with scheme
        else -> {
          val uri = Uri.parse(uriString)
          println("📷 [ANDROID] Parsed URI scheme: ${uri.scheme}, path: ${uri.path}")

          when (uri.scheme) {
            "file" -> {
              val file = File(uri.path!!)
              println("📷 [ANDROID] File exists: ${file.exists()}, readable: ${file.canRead()}")
              file.inputStream()
            }
            "content" -> {
              println("📷 [ANDROID] Using content resolver for content URI")
              appContext.reactContext!!.contentResolver.openInputStream(uri)
            }
            "http", "https" -> {
              println("📷 [ANDROID] Loading from URL: $uriString")
              URL(uriString).openStream()
            }
            else -> throw InputError("Unsupported URI scheme: ${uri.scheme}")
          }
        }
      }

      var bitmap = inputStream?.use { stream ->
        BitmapFactory.decodeStream(stream)
      }

      if (bitmap != null) {
        println("📷 [ANDROID] Successfully loaded bitmap: ${bitmap.width}x${bitmap.height}")

        // Apply EXIF orientation if we have a file path (matches iOS applyOrientationProperty behavior)
        if (filePath != null) {
          bitmap = applyExifOrientation(bitmap, filePath)
        }
      } else {
        println("📷 [ANDROID] Failed to decode bitmap from stream")
      }

      bitmap
    } catch (e: Exception) {
      println("📷 [ANDROID] Error loading bitmap from $uriString: ${e.javaClass.simpleName} - ${e.message}")
      e.printStackTrace()
      null
    }
  }

  /**
   * Applies EXIF orientation to bitmap (matches iOS CIImageOption.applyOrientationProperty)
   */
  private fun applyExifOrientation(bitmap: Bitmap, filePath: String): Bitmap {
    return try {
      val exif = ExifInterface(filePath)
      val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
      )

      println("📷 [ANDROID] EXIF orientation: $orientation")

      val matrix = Matrix()
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> {
          println("📷 [ANDROID] Rotating 90 degrees")
          matrix.postRotate(90f)
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> {
          println("📷 [ANDROID] Rotating 180 degrees")
          matrix.postRotate(180f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> {
          println("📷 [ANDROID] Rotating 270 degrees")
          matrix.postRotate(270f)
        }
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
          println("📷 [ANDROID] Flipping horizontally")
          matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
          println("📷 [ANDROID] Flipping vertically")
          matrix.postScale(1f, -1f)
        }
        else -> {
          println("📷 [ANDROID] No rotation needed")
          return bitmap
        }
      }

      val rotatedBitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
      )
      println("📷 [ANDROID] Rotated bitmap: ${rotatedBitmap.width}x${rotatedBitmap.height}")

      // Recycle the original bitmap if it's different
      if (rotatedBitmap != bitmap) {
        bitmap.recycle()
      }

      rotatedBitmap
    } catch (e: Exception) {
      println("📷 [ANDROID] Failed to apply EXIF orientation: ${e.message}")
      bitmap
    }
  }
  
  /**
   * Applies 3D LUT transformation to a bitmap using manual processing 
   * (RenderScript disabled due to linear LUT format complexity)
   */
  private fun applyLutToBitmap(inputBitmap: Bitmap, lutFilter: LutFilter): Bitmap {
    println("📷 [ANDROID] Applying LUT manually for better color accuracy")
    return applyLutManually(inputBitmap, lutFilter)
  }
  
  /**
   * Manual LUT processing using linear pixel lookup (matches iOS Core Image behavior)
   */
  private fun applyLutManually(inputBitmap: Bitmap, lutFilter: LutFilter): Bitmap {
    val width = inputBitmap.width
    val height = inputBitmap.height
    val outputBitmap = Bitmap.createBitmap(width, height, inputBitmap.config ?: Bitmap.Config.ARGB_8888)

    println("📷 [ANDROID] Processing ${width}x${height} image with LUT transformation")

    // Sample a few pixels to debug
    var changedPixels = 0
    var sampleCount = 0

    // Process each pixel through the LUT
    for (y in 0 until height) {
      for (x in 0 until width) {
        val pixel = inputBitmap.getPixel(x, y)
        // Transform pixel using trilinear interpolation LUT lookup
        val transformedPixel = lutFilter.transformPixel(pixel)
        outputBitmap.setPixel(x, y, transformedPixel)

        // Debug: check if pixels are actually changing
        if (pixel != transformedPixel) {
          changedPixels++
        }

        // Sample first few pixels for debugging
        if (sampleCount < 5) {
          val r = (pixel shr 16) and 0xFF
          val g = (pixel shr 8) and 0xFF
          val b = pixel and 0xFF
          val rNew = (transformedPixel shr 16) and 0xFF
          val gNew = (transformedPixel shr 8) and 0xFF
          val bNew = transformedPixel and 0xFF
          println("📷 [ANDROID] Pixel sample: RGB($r,$g,$b) -> RGB($rNew,$gNew,$bNew)")
          sampleCount++
        }
      }
    }

    println("📷 [ANDROID] LUT transformation complete: $changedPixels/${width*height} pixels changed")
    return outputBitmap
  }
  
  /**
   * Applies grain overlay to bitmap with specified blend mode
   */
  private fun applyGrainOverlay(
    baseBitmap: Bitmap, 
    grainBitmap: Bitmap, 
    opacity: Double, 
    blendMode: String
  ): Bitmap {
    val outputBitmap = Bitmap.createBitmap(
      baseBitmap.width,
      baseBitmap.height,
      baseBitmap.config ?: Bitmap.Config.ARGB_8888
    )
    
    val canvas = Canvas(outputBitmap)
    val paint = Paint()
    
    // Draw base image
    canvas.drawBitmap(baseBitmap, 0f, 0f, null)
    
    // Scale grain to cover the entire image
    val scaledGrain = Bitmap.createScaledBitmap(
      grainBitmap,
      baseBitmap.width,
      baseBitmap.height,
      true
    )
    
    // Apply blend mode and opacity
    paint.alpha = (opacity * 255).toInt()
    paint.xfermode = when (blendMode.lowercase()) {
      "multiply" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
      "screen" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
      "overlay" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
      else -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    
    // Draw grain overlay
    canvas.drawBitmap(scaledGrain, 0f, 0f, paint)
    
    return outputBitmap
  }
  
  /**
   * Saves bitmap to cache directory with EXIF metadata and returns the file URI
   */
  private fun saveBitmapToCache(bitmap: Bitmap, metadata: String, compressionQuality: Double): String {
    val cacheDir = appContext.reactContext!!.cacheDir
    val fileName = "${UUID.randomUUID()}.jpg"
    val file = File(cacheDir, fileName)

    try {
      // First save the bitmap
      FileOutputStream(file).use { out: FileOutputStream ->
        bitmap.compress(
          Bitmap.CompressFormat.JPEG,
          (compressionQuality * 100).toInt(),
          out
        )
      }

      // Add EXIF metadata if provided
      if (metadata.isNotEmpty()) {
        try {
          val exif = ExifInterface(file.absolutePath)

          // Add metadata to various EXIF fields for maximum compatibility
          exif.setAttribute(ExifInterface.TAG_USER_COMMENT, metadata)
          exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, metadata)

          // Save the metadata
          exif.saveAttributes()

          println("Image saved with EXIF metadata: $metadata")
        } catch (e: Exception) {
          println("Warning: Could not add EXIF metadata: ${e.message}")
          // Continue anyway - image is saved even if metadata fails
        }
      }

      println("Compressed image saved to cache: ${file.absolutePath}")
      return Uri.fromFile(file).toString()
    } catch (e: Exception) {
      throw InputError("Failed to save image to cache: ${e.message}")
    }
  }
  
  /**
   * Inner class representing a LUT filter with cached allocation
   */
  inner class LutFilter(
    val id: String,
    val lutBitmap: Bitmap,
    val dimension: Int
  ) {
    var intensity: Double = 1.0  // Filter intensity (0.0 to 1.0)

    val lutAllocation: Allocation by lazy {
      createLutAllocation(lutBitmap, dimension)
    }

    private val lutData: FloatArray by lazy {
      extractLutData(lutBitmap, dimension)
    }
    
    /**
     * Creates RenderScript allocation for 3D LUT
     */
    private fun createLutAllocation(lutBitmap: Bitmap, dimension: Int): Allocation {
      // Create 3D allocation for LUT
      val lutType = Type.Builder(renderScript, Element.U8_4(renderScript))
        .setX(dimension)
        .setY(dimension)
        .setZ(dimension)
        .create()
      
      val lutAllocation = Allocation.createTyped(renderScript, lutType)
      
      // Convert LUT bitmap to 3D data
      val lutData = convertLutBitmapTo3D(lutBitmap, dimension)
      lutAllocation.copyFromUnchecked(lutData)
      
      return lutAllocation
    }
    
    /**
     * Converts LUT bitmap to 3D byte array - iOS style linear pixel arrangement
     */
    private fun convertLutBitmapTo3D(lutBitmap: Bitmap, dimension: Int): ByteArray {
      val size = dimension * dimension * dimension * 4 // RGBA
      val lutData = ByteArray(size)
      
      println("📷 [ANDROID] Converting LUT bitmap: ${lutBitmap.width}x${lutBitmap.height} to ${dimension}³ LUT")
      
      // iOS processes pixels linearly (row by row), not in 2D grid layout
      // The bitmap contains exactly dimension³ pixels arranged in rows
      val expectedPixels = dimension * dimension * dimension
      val actualPixels = lutBitmap.width * lutBitmap.height
      
      if (actualPixels != expectedPixels) {
        println("📷 [ANDROID] WARNING: LUT size mismatch. Expected $expectedPixels pixels, got $actualPixels")
      }
      
      var index = 0
      // Read pixels row by row from the bitmap
      for (y in 0 until lutBitmap.height) {
        for (x in 0 until lutBitmap.width) {
          if (index >= size) break
          
          val pixel = lutBitmap.getPixel(x, y)
          
          // Extract RGBA components and store in byte array
          lutData[index++] = ((pixel shr 16) and 0xFF).toByte() // R
          lutData[index++] = ((pixel shr 8) and 0xFF).toByte()  // G  
          lutData[index++] = (pixel and 0xFF).toByte()          // B
          lutData[index++] = ((pixel shr 24) and 0xFF).toByte() // A
        }
      }
      
      println("📷 [ANDROID] Converted ${index / 4} pixels to LUT data")
      return lutData
    }
    
    /**
     * Extracts LUT data as float array for manual processing - iOS style linear arrangement
     */
    private fun extractLutData(lutBitmap: Bitmap, dimension: Int): FloatArray {
      val size = dimension * dimension * dimension * 4
      val lutData = FloatArray(size)
      
      var index = 0
      // Read pixels linearly row by row, matching iOS behavior
      for (y in 0 until lutBitmap.height) {
        for (x in 0 until lutBitmap.width) {
          if (index >= size) break
          
          val pixel = lutBitmap.getPixel(x, y)
          
          // Normalize to 0-1 range to match iOS float processing
          lutData[index++] = ((pixel shr 16) and 0xFF) / 255f // R
          lutData[index++] = ((pixel shr 8) and 0xFF) / 255f  // G
          lutData[index++] = (pixel and 0xFF) / 255f          // B
          lutData[index++] = ((pixel shr 24) and 0xFF) / 255f // A
        }
      }
      
      return lutData
    }
    
    /**
     * Transforms a single pixel using simple nearest-neighbor LUT lookup
     */
    fun transformPixelLinear(pixel: Int): Int {
      val r = (pixel shr 16) and 0xFF
      val g = (pixel shr 8) and 0xFF  
      val b = pixel and 0xFF
      val a = (pixel shr 24) and 0xFF
      
      // Simple approach: treat the LUT as a linear array where each position
      // corresponds directly to an RGB combination
      // For a 64x64x64 LUT, we need to map 0-255 RGB values to 0-63 indices
      val rLut = (r * dimension / 256).coerceIn(0, dimension - 1)
      val gLut = (g * dimension / 256).coerceIn(0, dimension - 1)
      val bLut = (b * dimension / 256).coerceIn(0, dimension - 1)
      
      // Try the standard cube indexing: z*width*height + y*width + x
      // where x=R, y=G, z=B (or vice versa)
      val lutIndex = (bLut * dimension * dimension + gLut * dimension + rLut) * 4
      
      if (lutIndex + 3 < lutData.size) {
        val newR = (lutData[lutIndex] * 255).toInt().coerceIn(0, 255)
        val newG = (lutData[lutIndex + 1] * 255).toInt().coerceIn(0, 255) 
        val newB = (lutData[lutIndex + 2] * 255).toInt().coerceIn(0, 255)
        
        return (a shl 24) or (newR shl 16) or (newG shl 8) or newB
      }
      
      // If standard indexing fails, try simple linear progression
      val simpleIndex = ((r + g + b) * lutData.size / (255 * 3 * 4)).coerceIn(0, lutData.size / 4 - 1) * 4
      
      if (simpleIndex + 3 < lutData.size) {
        val newR = (lutData[simpleIndex] * 255).toInt().coerceIn(0, 255)
        val newG = (lutData[simpleIndex + 1] * 255).toInt().coerceIn(0, 255) 
        val newB = (lutData[simpleIndex + 2] * 255).toInt().coerceIn(0, 255)
        
        return (a shl 24) or (newR shl 16) or (newG shl 8) or newB
      }
      
      // Return original pixel if both methods fail
      return pixel
    }
    
    /**
     * Transforms a single pixel using the LUT with intensity blending
     */
    fun transformPixel(pixel: Int): Int {
      val r = (pixel shr 16) and 0xFF
      val g = (pixel shr 8) and 0xFF
      val b = pixel and 0xFF
      val a = (pixel shr 24) and 0xFF

      // Normalize to LUT dimension
      val rf = r / 255f * (dimension - 1)
      val gf = g / 255f * (dimension - 1)
      val bf = b / 255f * (dimension - 1)

      // Trilinear interpolation
      val r0 = rf.toInt().coerceIn(0, dimension - 2)
      val g0 = gf.toInt().coerceIn(0, dimension - 2)
      val b0 = bf.toInt().coerceIn(0, dimension - 2)

      val rd = rf - r0
      val gd = gf - g0
      val bd = bf - b0

      // Sample 8 surrounding points and interpolate
      val filteredR = interpolate3D(r0, g0, b0, rd, gd, bd, 0)
      val filteredG = interpolate3D(r0, g0, b0, rd, gd, bd, 1)
      val filteredB = interpolate3D(r0, g0, b0, rd, gd, bd, 2)

      // Blend filtered result with original based on intensity
      val finalR = (filteredR * intensity + (r / 255f) * (1 - intensity)) * 255
      val finalG = (filteredG * intensity + (g / 255f) * (1 - intensity)) * 255
      val finalB = (filteredB * intensity + (b / 255f) * (1 - intensity)) * 255

      return (a shl 24) or
             (finalR.toInt() shl 16) or
             (finalG.toInt() shl 8) or
             finalB.toInt()
    }
    
    /**
     * Performs trilinear interpolation for a single channel
     */
    private fun interpolate3D(
      r0: Int, g0: Int, b0: Int,
      rd: Float, gd: Float, bd: Float,
      channel: Int
    ): Float {
      val idx000 = ((b0 * dimension + g0) * dimension + r0) * 4 + channel
      val idx001 = ((b0 * dimension + g0) * dimension + r0 + 1) * 4 + channel
      val idx010 = ((b0 * dimension + g0 + 1) * dimension + r0) * 4 + channel
      val idx011 = ((b0 * dimension + g0 + 1) * dimension + r0 + 1) * 4 + channel
      val idx100 = (((b0 + 1) * dimension + g0) * dimension + r0) * 4 + channel
      val idx101 = (((b0 + 1) * dimension + g0) * dimension + r0 + 1) * 4 + channel
      val idx110 = (((b0 + 1) * dimension + g0 + 1) * dimension + r0) * 4 + channel
      val idx111 = (((b0 + 1) * dimension + g0 + 1) * dimension + r0 + 1) * 4 + channel
      
      val c000 = lutData[idx000]
      val c001 = lutData[idx001]
      val c010 = lutData[idx010]
      val c011 = lutData[idx011]
      val c100 = lutData[idx100]
      val c101 = lutData[idx101]
      val c110 = lutData[idx110]
      val c111 = lutData[idx111]
      
      // Trilinear interpolation
      val c00 = c000 * (1 - rd) + c001 * rd
      val c01 = c010 * (1 - rd) + c011 * rd
      val c10 = c100 * (1 - rd) + c101 * rd
      val c11 = c110 * (1 - rd) + c111 * rd
      
      val c0 = c00 * (1 - gd) + c01 * gd
      val c1 = c10 * (1 - gd) + c11 * gd
      
      return c0 * (1 - bd) + c1 * bd
    }
  }
}