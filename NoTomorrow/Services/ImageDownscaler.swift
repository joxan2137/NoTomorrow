import UIKit

/// Shrinks a camera/library photo to something worth uploading: ≤ 1024 px on the long edge, JPEG 0.8, orientation baked in,
/// metadata stripped (re-encoding through a renderer drops EXIF, including location).
enum ImageDownscaler {
    static func jpegData(from image: UIImage, maxLongEdge: CGFloat = 1024, quality: CGFloat = 0.8) -> Data? {
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
}

extension UIImage {
    /// Convenience for `ImageDownscaler.jpegData(from:)`.
    func downscaledJPEG(maxLongEdge: CGFloat = 1024, quality: CGFloat = 0.8) -> Data? {
        ImageDownscaler.jpegData(from: self, maxLongEdge: maxLongEdge, quality: quality)
    }
}
