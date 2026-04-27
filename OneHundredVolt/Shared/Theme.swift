import SwiftUI

// MARK: - 一百伏特 设计规范（来自 DESIGN.md）

enum Theme {

    // MARK: Colors
    enum Colors {
        static let background       = Color(hex: "#0A0A0C")
        static let secondaryBackground = Color(hex: "#141418")
        static let cardBackground   = Color(hex: "#1E1E24")
        static let accent           = Color(hex: "#00D4AA")   // 青绿色 — "电"感
        static let secondaryAccent  = Color(hex: "#FFB800")   // 琥珀黄 — 能量感
        static let textPrimary      = Color.white
        static let textSecondary    = Color(hex: "#8E8E93")
        static let divider          = Color(hex: "#2C2C30")
        static let success          = Color(hex: "#30D158")
        static let warning          = Color(hex: "#FF9F0A")

        static let accentGradient = LinearGradient(
            colors: [Color(hex: "#00D4AA"), Color(hex: "#0097A7")],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    // MARK: Corner Radius
    enum CornerRadius {
        static let card: CGFloat      = 16
        static let button: CGFloat    = 12
        static let textField: CGFloat = 10
        static let cover: CGFloat     = 8
    }

    // MARK: Typography
    enum Typography {
        static let largeTitle  = Font.system(size: 24, weight: .bold)
        static let title       = Font.system(size: 20, weight: .semibold)
        static let body        = Font.system(size: 16, weight: .regular)
        static let subheadline = Font.system(size: 14, weight: .medium)
        static let caption     = Font.system(size: 13, weight: .regular)
        static let mono        = Font.system(size: 13, weight: .regular, design: .monospaced)
    }

    // MARK: Spacing
    enum Spacing {
        static let xs: CGFloat  = 4
        static let sm: CGFloat  = 8
        static let md: CGFloat  = 16
        static let lg: CGFloat  = 24
        static let xl: CGFloat  = 32
    }
}

// MARK: - Color Hex init
extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3:  (a, r, g, b) = (255, (int >> 8)*17, (int >> 4 & 0xF)*17, (int & 0xF)*17)
        case 6:  (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8:  (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default: (a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(.sRGB,
                  red:   Double(r) / 255,
                  green: Double(g) / 255,
                  blue:  Double(b) / 255,
                  opacity: Double(a) / 255)
    }
}
