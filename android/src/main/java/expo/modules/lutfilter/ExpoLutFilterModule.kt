package expo.modules.lutfilter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
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
      compression: Double, 
      withGrain: Boolean ->
      
      try {
        println("📷 [ANDROID] applyLUT called with:")
        println("📷 [ANDROID]   inputImageUri: $inputImageUri")
        println("📷 [ANDROID]   filterId: $filterId")
        println("📷 [ANDROID]   lutUri: $lutUri")
        println("📷 [ANDROID]   lutDimension: $lutDimension")
        
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
        
        println("📷 [ANDROID] LUT filter ready, applying transformation")
        
        // Apply LUT transformation
        var processedBitmap = applyLutToBitmap(inputBitmap, lutFilter)
        
        // Apply grain overlay if requested
        if (withGrain && grainBitmap != null) {
          println("Applying grain...")
          processedBitmap = applyGrainOverlay(processedBitmap, grainBitmap!!, grainOpacity, grainBlendMode)
          println("Applied grain!")
        }
        
        // Save to cache and return URI
        val outputUri = saveBitmapToCache(processedBitmap, compression)
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
   */
  private fun loadBitmapFromUri(uriString: String): Bitmap? {
    return try {
      println("📷 [ANDROID] Attempting to load bitmap from: $uriString")
      val uri = Uri.parse(uriString)
      println("📷 [ANDROID] Parsed URI scheme: ${uri.scheme}, path: ${uri.path}")
      
      val inputStream = when (uri.scheme) {
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
      
      val bitmap = inputStream?.use { stream ->
        BitmapFactory.decodeStream(stream)
      }
      
      if (bitmap != null) {
        println("📷 [ANDROID] Successfully loaded bitmap: ${bitmap.width}x${bitmap.height}")
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
   * Applies 3D LUT transformation to a bitmap using RenderScript
   */
  private fun applyLutToBitmap(inputBitmap: Bitmap, lutFilter: LutFilter): Bitmap {
    // Create output bitmap
    val outputBitmap = Bitmap.createBitmap(
      inputBitmap.width, 
      inputBitmap.height, 
      inputBitmap.config ?: Bitmap.Config.ARGB_8888
    )
    
    try {
      // Create allocations for input and output
      val inputAllocation = Allocation.createFromBitmap(renderScript, inputBitmap)
      val outputAllocation = Allocation.createFromBitmap(renderScript, outputBitmap)
      
      // Apply 3D LUT
      val script3dLut = ScriptIntrinsic3DLUT.create(renderScript, Element.U8_4(renderScript))
      script3dLut.setLUT(lutFilter.lutAllocation)
      script3dLut.forEach(inputAllocation, outputAllocation)
      
      // Copy result to output bitmap
      outputAllocation.copyTo(outputBitmap)
      
      // Clean up
      inputAllocation.destroy()
      outputAllocation.destroy()
      script3dLut.destroy()
      
    } catch (e: Exception) {
      println("Error applying LUT with RenderScript: ${e.message}")
      // Fallback to manual processing if RenderScript fails
      return applyLutManually(inputBitmap, lutFilter)
    }
    
    return outputBitmap
  }
  
  /**
   * Manual fallback for LUT processing without RenderScript
   */
  private fun applyLutManually(inputBitmap: Bitmap, lutFilter: LutFilter): Bitmap {
    val width = inputBitmap.width
    val height = inputBitmap.height
    val outputBitmap = Bitmap.createBitmap(width, height, inputBitmap.config ?: Bitmap.Config.ARGB_8888)
    
    // Process each pixel
    for (y in 0 until height) {
      for (x in 0 until width) {
        val pixel = inputBitmap.getPixel(x, y)
        val newPixel = lutFilter.transformPixel(pixel)
        outputBitmap.setPixel(x, y, newPixel)
      }
    }
    
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
   * Saves bitmap to cache directory and returns the file URI
   */
  private fun saveBitmapToCache(bitmap: Bitmap, compressionQuality: Double): String {
    val cacheDir = appContext.reactContext!!.cacheDir
    val fileName = "${UUID.randomUUID()}.jpg"
    val file = File(cacheDir, fileName)
    
    try {
      FileOutputStream(file).use { out: FileOutputStream ->
        bitmap.compress(
          Bitmap.CompressFormat.JPEG, 
          (compressionQuality * 100).toInt(), 
          out
        )
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
     * Converts LUT bitmap (usually 512x512 for 64x64x64) to 3D byte array
     */
    private fun convertLutBitmapTo3D(lutBitmap: Bitmap, dimension: Int): ByteArray {
      val size = dimension * dimension * dimension * 4 // RGBA
      val lutData = ByteArray(size)
      
      // Calculate how the LUT is laid out in 2D
      // Common format: 8x8 grid of 64x64 squares for 64^3 LUT
      val squaresPerRow = kotlin.math.sqrt(dimension.toDouble()).toInt()
      val squareSize = dimension
      
      var index = 0
      for (b in 0 until dimension) { // Blue channel varies slowest
        for (g in 0 until dimension) { // Green channel
          for (r in 0 until dimension) { // Red channel varies fastest
            // Calculate position in 2D LUT image
            val squareX = (b % squaresPerRow) * squareSize
            val squareY = (b / squaresPerRow) * squareSize
            val pixelX = squareX + r
            val pixelY = squareY + g
            
            // Get pixel from LUT image
            val pixel = lutBitmap.getPixel(pixelX, pixelY)
            
            // Extract RGBA and store in byte array
            lutData[index++] = ((pixel shr 16) and 0xFF).toByte() // R
            lutData[index++] = ((pixel shr 8) and 0xFF).toByte()  // G
            lutData[index++] = (pixel and 0xFF).toByte()          // B
            lutData[index++] = ((pixel shr 24) and 0xFF).toByte() // A
          }
        }
      }
      
      return lutData
    }
    
    /**
     * Extracts LUT data as float array for manual processing
     */
    private fun extractLutData(lutBitmap: Bitmap, dimension: Int): FloatArray {
      val size = dimension * dimension * dimension * 4
      val lutData = FloatArray(size)
      
      val squaresPerRow = kotlin.math.sqrt(dimension.toDouble()).toInt()
      val squareSize = dimension
      
      var index = 0
      for (b in 0 until dimension) {
        for (g in 0 until dimension) {
          for (r in 0 until dimension) {
            val squareX = (b % squaresPerRow) * squareSize
            val squareY = (b / squaresPerRow) * squareSize
            val pixelX = squareX + r
            val pixelY = squareY + g
            
            val pixel = lutBitmap.getPixel(pixelX, pixelY)
            
            // Normalize to 0-1 range
            lutData[index++] = ((pixel shr 16) and 0xFF) / 255f // R
            lutData[index++] = ((pixel shr 8) and 0xFF) / 255f  // G
            lutData[index++] = (pixel and 0xFF) / 255f          // B
            lutData[index++] = ((pixel shr 24) and 0xFF) / 255f // A
          }
        }
      }
      
      return lutData
    }
    
    /**
     * Transforms a single pixel using the LUT (for manual fallback)
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
      val newR = interpolate3D(r0, g0, b0, rd, gd, bd, 0)
      val newG = interpolate3D(r0, g0, b0, rd, gd, bd, 1)
      val newB = interpolate3D(r0, g0, b0, rd, gd, bd, 2)
      
      return (a shl 24) or 
             ((newR * 255).toInt() shl 16) or 
             ((newG * 255).toInt() shl 8) or 
             (newB * 255).toInt()
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