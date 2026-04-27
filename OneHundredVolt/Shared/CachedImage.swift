import SwiftUI
import UIKit
import CryptoKit

// MARK: - 图片缓存（内存 + 磁盘双层）

final class ImageCache {
    static let shared = ImageCache()

    // MARK: 内存缓存
    private let memory = NSCache<NSString, UIImage>()

    // MARK: 磁盘缓存目录
    private let diskCacheURL: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("ImageCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private init() {
        memory.countLimit = 200
        memory.totalCostLimit = 50 * 1024 * 1024  // 50MB
    }

    // MARK: - 读取（内存 → 磁盘）

    func get(_ urlString: String) -> UIImage? {
        // 1. 内存命中
        if let img = memory.object(forKey: urlString as NSString) { return img }
        // 2. 磁盘命中
        let fileURL = diskFileURL(for: urlString)
        guard let data = try? Data(contentsOf: fileURL),
              let img = UIImage(data: data) else { return nil }
        // 回写内存
        let cost = Int(img.size.width * img.size.height * 4)
        memory.setObject(img, forKey: urlString as NSString, cost: cost)
        return img
    }

    // MARK: - 写入（内存 + 磁盘）

    func set(_ image: UIImage, for urlString: String) {
        let cost = Int(image.size.width * image.size.height * 4)
        memory.setObject(image, forKey: urlString as NSString, cost: cost)
        // 异步写磁盘，不阻塞主线程
        let fileURL = diskFileURL(for: urlString)
        DispatchQueue.global(qos: .utility).async {
            guard let data = image.jpegData(compressionQuality: 0.85) else { return }
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    // MARK: - 磁盘文件路径（用 URL 的 MD5 作文件名）

    private func diskFileURL(for urlString: String) -> URL {
        let hash = Insecure.MD5.hash(data: Data(urlString.utf8))
            .map { String(format: "%02x", $0) }.joined()
        return diskCacheURL.appendingPathComponent(hash + ".jpg")
    }
}

// MARK: - 异步加载 + 双层缓存的图片视图

struct CachedImage<Placeholder: View>: View {
    let urlString: String?
    let placeholder: () -> Placeholder

    @State private var image: UIImage?
    @State private var loadTask: Task<Void, Never>?

    init(urlString: String?, @ViewBuilder placeholder: @escaping () -> Placeholder) {
        self.urlString = urlString
        self.placeholder = placeholder
        // init 中从缓存（内存或磁盘）取初始值，命中时视图一出现就直接显示图片
        if let key = urlString, !key.isEmpty {
            _image = State(initialValue: ImageCache.shared.get(key))
        }
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder()
            }
        }
        .onAppear { load() }
        .onDisappear { loadTask?.cancel() }
        .onChange(of: urlString) { load() }
    }

    private func load() {
        loadTask?.cancel()
        guard let urlString, !urlString.isEmpty, let url = URL(string: urlString) else {
            image = nil
            return
        }
        // 命中缓存（内存或磁盘）直接用
        if let cached = ImageCache.shared.get(urlString) {
            image = cached
            return
        }
        // 网络下载
        loadTask = Task {
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  !Task.isCancelled,
                  let uiImage = UIImage(data: data) else { return }
            ImageCache.shared.set(uiImage, for: urlString)
            await MainActor.run { image = uiImage }
        }
    }
}
