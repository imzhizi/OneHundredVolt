import SwiftUI

/// 全屏播放器
struct PlayerView: View {
    @Environment(\.dismiss) private var dismiss
    private let player = AudioPlayerService.shared

    @State private var isDraggingProgress = false
    @State private var dragProgress: Double = 0
    @State private var showSleepPicker = false
    @State private var showSpeedPicker = false
    @State private var postDetailURL: URL? = nil

    // 下拉关闭手势
    @State private var dragOffset: CGFloat = 0
    private let dismissThreshold: CGFloat = 120

    var body: some View {
        ZStack {
            Theme.Colors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                // 顶部导航
                topBar

                // 主体区域：均匀撑满剩余空间
                VStack(spacing: 0) {
                    Spacer()

                    // 封面
                    coverSection

                    Spacer()

                    // 标题
                    titleSection
                        .padding(.horizontal, Theme.Spacing.lg)

                    Spacer()

                    // 进度条
                    progressSection
                        .padding(.horizontal, Theme.Spacing.lg)

                    Spacer()

                    // 控制按钮
                    controlsSection
                        .padding(.horizontal, Theme.Spacing.lg)

                    Spacer()

                    // 速度 + 睡眠
                    secondaryControls
                        .padding(.horizontal, Theme.Spacing.lg)

                    Spacer()
                }
            }
        }
        // 下拉跟手偏移
        .offset(y: max(0, dragOffset))
        // 背景随拖动逐渐透明
        .opacity(dragOffset > 0 ? Double(1 - dragOffset / 400) : 1)
        // 下拉关闭手势（只在竖向拖动时响应，避免干扰进度条横向手势）
        .gesture(
            DragGesture(minimumDistance: 20, coordinateSpace: .local)
                .onChanged { value in
                    // 仅当纵向分量明显大于横向时才接管（排除进度条拖动）
                    guard !isDraggingProgress,
                          abs(value.translation.height) > abs(value.translation.width),
                          value.translation.height > 0
                    else { return }
                    dragOffset = value.translation.height
                }
                .onEnded { value in
                    if dragOffset > dismissThreshold {
                        dismiss()
                    } else {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.7)) {
                            dragOffset = 0
                        }
                    }
                }
        )
        // 播放列表全部播完时自动关闭播放器
        .onReceive(NotificationCenter.default.publisher(for: .playbackDidFinishAll)) { _ in
            dismiss()
        }
        // 睡眠定时弹窗
        .confirmationDialog("睡眠定时", isPresented: $showSleepPicker, titleVisibility: .visible) {
            ForEach(AudioPlayerService.SleepDuration.allCases, id: \.self) { d in
                Button(d.label) { player.setSleepTimer(d) }
            }
            Button("取消", role: .cancel) {}
        }
        // 速度选择弹窗
        .confirmationDialog("播放速度", isPresented: $showSpeedPicker, titleVisibility: .visible) {
            ForEach([0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 2.0] as [Float], id: \.self) { rate in
                Button("\(rate == 1.0 ? "1.0" : String(format: "%.2g", rate))x") {
                    player.playbackRate = rate
                }
            }
            Button("取消", role: .cancel) {}
        }
        // 单集详情网页
        .sheet(item: $postDetailURL) { url in
            SafariView(url: url)
                .ignoresSafeArea()
        }
    }

    // MARK: - 顶部导航

    private var topBar: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Theme.Colors.textPrimary)
            }

            Spacer()

            Text("正在播放")
                .font(Theme.Typography.subheadline)
                .foregroundColor(Theme.Colors.textSecondary)

            Spacer()

            // 占位
            Color.clear.frame(width: 24, height: 24)
        }
        .padding(.horizontal, Theme.Spacing.lg)
        .padding(.top, Theme.Spacing.md)
        .padding(.bottom, Theme.Spacing.sm)
    }

    // MARK: - 封面

    private var coverSection: some View {
        let item = player.currentItem
        return GeometryReader { geo in
            let size = min(geo.size.width, 320.0)
            CachedImage(urlString: item?.coverUrl) {
                RoundedRectangle(cornerRadius: Theme.CornerRadius.card)
                    .fill(Theme.Colors.cardBackground)
                    .overlay(
                        Image(systemName: "waveform")
                            .font(.system(size: 44))
                            .foregroundColor(Theme.Colors.textSecondary)
                    )
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.card))
            .shadow(color: .black.opacity(0.4), radius: 20, y: 10)
            .scaleEffect(player.isPlaying ? 1.0 : 0.92)
            .animation(.spring(response: 0.4), value: player.isPlaying)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .aspectRatio(1, contentMode: .fit)
        .frame(maxWidth: 320)
        .frame(maxWidth: .infinity)
    }

    // MARK: - 标题

    private var titleSection: some View {
        Button {
            if let id = player.currentItem?.id {
                postDetailURL = URL(string: "https://afdian.com/p/\(id)")
            }
        } label: {
            HStack(alignment: .top, spacing: 4) {
                Text(player.currentItem?.title ?? "–")
                    .font(Theme.Typography.title)
                    .foregroundColor(Theme.Colors.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(Theme.Colors.textSecondary)
                    .padding(.top, 4)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - 进度条

    private var progressSection: some View {
        VStack(spacing: Theme.Spacing.sm) {
            // 拖动进度条
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Theme.Colors.divider)
                        .frame(height: 4)

                    Capsule()
                        .fill(Theme.Colors.accent)
                        .frame(
                            width: geo.size.width * (isDraggingProgress ? dragProgress : player.progressRatio),
                            height: 4
                        )

                    // 拖动手柄
                    Circle()
                        .fill(Theme.Colors.accent)
                        .frame(width: 16, height: 16)
                        .offset(x: geo.size.width * (isDraggingProgress ? dragProgress : player.progressRatio) - 8)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            isDraggingProgress = true
                            dragProgress = max(0, min(1, value.location.x / geo.size.width))
                        }
                        .onEnded { value in
                            let ratio = max(0, min(1, value.location.x / geo.size.width))
                            player.seek(to: player.duration * ratio)
                            isDraggingProgress = false
                        }
                )
            }
            .frame(height: 16)

            // 时间
            HStack {
                Text((isDraggingProgress ? player.duration * dragProgress : player.currentTime).formatted)
                    .font(Theme.Typography.mono)
                    .foregroundColor(Theme.Colors.textSecondary)
                Spacer()
                Text(player.duration.formatted)
                    .font(Theme.Typography.mono)
                    .foregroundColor(Theme.Colors.textSecondary)
            }
        }
    }

    // MARK: - 主控制按钮

    private var controlsSection: some View {
        HStack(spacing: 0) {
            // 快退 15s
            Button { player.skipBackward() } label: {
                Image(systemName: "gobackward.15")
                    .font(.system(size: 30))
                    .foregroundColor(Theme.Colors.textPrimary)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(ScaleButtonStyle())

            // 播放 / 暂停（大按钮）
            Button { player.togglePlayPause() } label: {
                ZStack {
                    Circle()
                        .fill(Theme.Colors.accent)
                        .frame(width: 68, height: 68)
                    if player.isLoading {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.black)
                            .offset(x: player.isPlaying ? 0 : 2)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(ScaleButtonStyle(scale: 0.95))

            // 快进 30s
            Button { player.skipForward() } label: {
                Image(systemName: "goforward.30")
                    .font(.system(size: 30))
                    .foregroundColor(Theme.Colors.textPrimary)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(ScaleButtonStyle())
        }
    }

    // MARK: - 次级控制

    private var secondaryControls: some View {
        HStack {
            // 速度
            Button { showSpeedPicker = true } label: {
                Text(speedLabel)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.accent)
                    .padding(.horizontal, Theme.Spacing.sm)
                    .padding(.vertical, 6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Theme.Colors.accent.opacity(0.5), lineWidth: 1)
                    )
            }
            .buttonStyle(ScaleButtonStyle())

            Spacer()

            // 睡眠定时
            Button { showSleepPicker = true } label: {
                HStack(spacing: 4) {
                    Image(systemName: "moon.zzz.fill")
                    if player.sleepRemainingSeconds > 0 {
                        Text(TimeInterval(player.sleepRemainingSeconds).formatted)
                            .font(Theme.Typography.mono)
                    } else {
                        Text("定时")
                            .font(Theme.Typography.caption)
                    }
                }
                .foregroundColor(
                    player.sleepRemainingSeconds > 0 ? Theme.Colors.secondaryAccent : Theme.Colors.textSecondary
                )
            }
            .buttonStyle(ScaleButtonStyle())

            Spacer()

            // 播放队列：dismiss 自身，然后通知各层 View 回首页播放列表
            Button {
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    NotificationCenter.default.post(name: .navigateToHomePlaylist, object: nil)
                }
            } label: {
                Image(systemName: "list.bullet")
                    .font(.system(size: 22))
                    .foregroundColor(Theme.Colors.textSecondary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(ScaleButtonStyle())
        }
    }

    // MARK: - 辅助计算

    private var speedLabel: String {
        let r = player.playbackRate
        return r == 1.0 ? "1.0x" : String(format: "%.2gx", r)
    }

}
