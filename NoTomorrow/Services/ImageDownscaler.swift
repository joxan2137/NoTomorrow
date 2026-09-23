import UIKit
import ImageIO
import UniformTypeIdentifiers

/// Shrinks a camera/library photo to something worth uploading: ≤ 1024 px on the long edge (1600 for a nutrition
/// label, whose small print needs the pixels), JPEG 0.8, orientation baked in, metadata stripped (re-encoding drops
/// EXIF, including location). Both paths are safe off the main thread; call them from a background task.
enum ImageDownscaler {
    static let plateLongEdge: CGFloat = 1024
    static let labelLongEdge: CGFloat = 1600

    static func jpegData(from image: UIImage, maxLongEdge: CGFloat = plateLongEdge, quality: CGFloat = 0.8) -> Data? {
        // `size` is already orientation-corrected (points); multiply by scale to reason in pixels.
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        guard pixelWidth > 0, pixelHeight > 0 else { return nil }

        let longEdge = max(pixelWidth, pixelHeight)
        let factor = longEdge > maxLongEdge ? maxLongEdge / longEdge : 1
        let target = CGSize(width: (pixelWidth * factor).rounded(.down), height: (pixelHeight * factor).rounded(.down))
        guard target.width >= 1, target.height >= 1 else { return nil }

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1            // 1 pt == 1 px so `target` is the pixel size
        format.opaque = true        // JPEG has no alpha; avoids a black-on-transparent surprise
        format.preferredRange = .standard

        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        // `draw(in:)` applies `imageOrientation`, so the output is upright without touching EXIF.
        let rendered = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        return rendered.jpegData(compressionQuality: quality)
    }

    /// Encoded photo bytes (JPEG, HEIC, PNG…) → a small JPEG, without decoding the full-size image: ImageIO builds the
    /// thumbnail straight from the file (orientation applied) and the re-encode copies no metadata.
    static func jpegData(from data: Data, maxLongEdge: CGFloat = plateLongEdge, quality: CGFloat = 0.8) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxLongEdge),
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, thumbnail,
                                   [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}

extension UIImage {
    /// Convenience for `ImageDownscaler.jpegData(from:)`.
    func downscaledJPEG(maxLongEdge: CGFloat = ImageDownscaler.plateLongEdge, quality: CGFloat = 0.8) -> Data? {
        ImageDownscaler.jpegData(from: self, maxLongEdge: maxLongEdge, quality: quality)
    }
}

/// A photo as the pickers hand it over: the camera gives a decoded image, the library the file's bytes.
enum PickedPhoto {
    case image(UIImage)
    case data(Data)

    /// The upload JPEG. Heavy for a full-size photo, so run it off the main thread.
    func jpeg(maxLongEdge: CGFloat) -> Data? {
        switch self {
        case .image(let image): ImageDownscaler.jpegData(from: image, maxLongEdge: maxLongEdge)
        case .data(let data): ImageDownscaler.jpegData(from: data, maxLongEdge: maxLongEdge)
        }
    }

    /// Downscales on a background thread; nil when the photo cannot be read.
    func preparedJPEG(maxLongEdge: CGFloat) async -> Data? {
        await Task.detached(priority: .userInitiated) { jpeg(maxLongEdge: maxLongEdge) }.value
    }
}
