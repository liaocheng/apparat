package apparat.tools.reducer

import apparat.tools._
import apparat.utils._
import apparat.swf._

import java.awt.image.{BufferedImage => JBufferedImage}
import javax.imageio.{IIOImage => JIIOImage}
import javax.imageio.{ImageIO => JImageIO}
import javax.imageio.{ImageWriteParam => JImageWriteParam}
import java.util.zip.{Inflater => JInflater}
import java.util.zip.{Deflater => JDeflater}
import apparat.actors.Futures._
import java.io.{File => JFile, ByteArrayOutputStream => JByteArrayOutputStream, ByteArrayInputStream => JByteArrayInputStream}
import apparat.abc.Abc
import apparat.abc.analysis.AbcConstantPoolBuilder

object Reducer {
	def main(args: Array[String]): Unit = ApparatApplication(new ReducerTool, args)

	class ReducerTool extends ApparatTool {
		var deblock = 0.0f
		var quality = 0.99f
		var input: JFile = _
		var output: JFile = _
		var mergeABC: Boolean = false

		override def name: String = "Reducer"

		override def help: String = """  -i [file]	Input file
  -o [file]	Output file (optional)
  -d [float]	Strength of deblocking filter (optional)
  -q [float]	Quality from 0.0 to 1.0 (optional)
  -m [true|false] Merge ABC files"""

		override def configure(config: ApparatConfiguration): Unit = configure(ReducerConfigurationFactory fromConfiguration config)
		
		def configure(config: ReducerConfiguration): Unit = {
			input = config.input
			output = config.output
			quality = config.quality
			deblock = config.deblock
			mergeABC = config.mergeABC
		}

		override def run() = {
			SwfTags.tagFactory = (kind: Int) => kind match {
				case SwfTags.DefineBitsLossless2 => Some(new DefineBitsLossless2)
				case SwfTags.FileAttributes => Some(new FileAttributes)
				case SwfTags.DoABC if mergeABC => Some(new DoABC)
				case SwfTags.DoABC1 if mergeABC => Some(new DoABC)
				case _ => None
			}
			val source = input
			val target = output
			val l0 = source length
			val cont = TagContainer fromFile source
			cont.tags = cont.tags filterNot (tag => tag.kind == SwfTags.Metadata || tag.kind == SwfTags.ProductInfo) map reduce

			if(mergeABC) {
				var buffer: Option[Abc] = None
				var result = List.empty[SwfTag]

				for(tag <- cont.tags) {
					tag match {
						case doABC: DoABC => {
							val abc = Abc fromDoABC doABC
							abc.loadBytecode()

							buffer = buffer match {
								case Some(b) => Some(b + abc)
								case None => Some(abc)
							}
						}
						case o => {
							buffer match {
								case Some(b) => {
									val doABC = new DoABC()

									doABC.flags = 1
									doABC.name = "apparat.googlecode.com"

									b.bytecodeAvailable = true
									ApparatLog("Building new cpool ...")
									b.cpool = AbcConstantPoolBuilder using b//TODO we have to get rid of this!
									b.saveBytecode()
									b write doABC

									result = o :: doABC :: result
								}
								case None => result = o :: result
							}
							
							buffer = None
						}
					}
				}

				cont.tags = result.reverse
			}

			cont write target
			val delta = l0 - (target length)
			ApparatLog("Compression ratio: " + ((delta).asInstanceOf[Float] / l0.asInstanceOf[Float]) * 100.0f + "%")
			ApparatLog("Total bytes: " + delta)
		}

		private def reduce(tag: SwfTag) = tag.kind match {
			case SwfTags.DefineBitsLossless2 => {
				val dbl2 = tag.asInstanceOf[DefineBitsLossless2]
				if (5 == dbl2.bitmapFormat && (dbl2.bitmapWidth * dbl2.bitmapHeight) > 1024) {
					lossless2jpg(dbl2)
				} else {
					dbl2
				}
			}
			case SwfTags.FileAttributes => {
				val fileAttributes = tag.asInstanceOf[FileAttributes]
				val result = new FileAttributes()

				result.actionScript3 = fileAttributes.actionScript3
				result.hasMetadata = false
				result.useDirectBlit = fileAttributes.useDirectBlit
				result.useGPU = fileAttributes.useGPU
				result.useNetwork = fileAttributes.useNetwork

				result
			}
			case _ => tag
		}

		private def lossless2jpg(tag: DefineBitsLossless2) = {
			val width = tag.bitmapWidth
			val height = tag.bitmapHeight
			val inflater = new JInflater();
			val lossless = new Array[Byte]((width * height) << 2)
			val alphaData = new Array[Byte](width * height)
			var needsAlpha = false

			// decompress zlib data

			inflater setInput tag.zlibBitmapData

			var offset = -1
			while (0 != offset && !inflater.finished) {
				offset = inflater inflate lossless
				if (0 == offset && inflater.needsInput) {
					error("Need more input.")
				}
			}

			// create buffered image
			// fill alpha data

			val buffer = new JBufferedImage(width, height, JBufferedImage.TYPE_INT_ARGB)

			for (y <- 0 until height; x <- 0 until width) {
				val index = (x << 2) + (y << 2) * width
				val alpha = lossless(index) & 0xff
				val red = lossless(index + 1) & 0xff
				val green = lossless(index + 2) & 0xff
				val blue = lossless(index + 3) & 0xff

				if (0xff != alpha) {
					needsAlpha = true
				}

				// useless to go from premultiplied to normal
				//
				//if(alpha > 0 && alpha < 0xff) {
				//  val alphaMultiplier = 255.0f / alpha
				//  red = clamp(red * alphaMultiplier)
				//  green = clamp(green * alphaMultiplier)
				//  blue = clamp(blue * alphaMultiplier)
				//}

				alphaData(x + y * width) = lossless(index)
				buffer.setRGB(x, y, (0xff << 0x18) | (red << 0x10) | (green << 0x08) | blue)
			}

			// compress alpha data

			val deflater = new JDeflater(JDeflater.BEST_COMPRESSION)
			deflater setInput alphaData
			deflater.finish()

			val compressBuffer = new Array[Byte](0x400)
			var numBytesCompressed = 0
			val alphaOutput = new JByteArrayOutputStream()

			do {
				numBytesCompressed = deflater deflate compressBuffer
				alphaOutput write (compressBuffer, 0, numBytesCompressed)
			} while (0 != numBytesCompressed)

			alphaOutput.flush()
			alphaOutput.close()

			// create jpg

			val writer = JImageIO getImageWritersByFormatName ("jpg") next ()
			val imageOutput = new JByteArrayOutputStream()

			writer setOutput JImageIO.createImageOutputStream(imageOutput)

			val writeParam = writer.getDefaultWriteParam()
			writeParam setCompressionMode JImageWriteParam.MODE_EXPLICIT
			writeParam setCompressionQuality quality
			writer write (null, new JIIOImage(buffer.getData(), null, null), writeParam)
			imageOutput.flush()
			imageOutput.close()
			writer.dispose()

			// create tag

			val newTag: SwfTag with KnownLengthTag with DefineTag = if (needsAlpha) {
				if (0.0f == deblock) {
					val dbj3 = new DefineBitsJPEG3()
					dbj3.alphaData = alphaOutput.toByteArray()
					dbj3.imageData = imageOutput.toByteArray()
					dbj3
				} else {
					val dbj4 = new DefineBitsJPEG4()
					dbj4.alphaData = alphaOutput.toByteArray()
					dbj4.imageData = imageOutput.toByteArray()
					dbj4.deblock = deblock
					dbj4
				}
			} else {
				val dbj2 = new DefineBitsJPEG2()
				dbj2.imageData = imageOutput.toByteArray()
				dbj2
			}

			if (newTag.length < tag.length) {
				ApparatLog succ ("Compressed character " + tag.characterID)
				newTag.characterID = tag.characterID
				newTag
			} else {
				tag
			}
		}

		private def clamp(value: Float): Int = value match {
			case x if x < 0 => 0
			case x if x > 255 => 255
			case x => x.asInstanceOf[Int]
		}
	}
}