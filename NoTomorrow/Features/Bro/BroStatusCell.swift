import SwiftUI

/// One attendance cell: attended = ember ring + check, missed/cancelled = rose ring + x,
/// planned = faint ring, confirmed = white "In", rest = nothing.
struct BroStatusCell: View {
    var state: DayState
    var size: CGFloat = 30

    var body: some View {
        ZStack {
            switch state {
            case .rest:
                Color.clear
            case .planned:
                Circle().strokeBorder(NT.Colors.border, lineWidth: 1.5)
            case .confirmed:
                Circle().fill(NT.Colors.ink)
                Text("bro.in")
                    .font(.system(size: size >= 30 ? 12 : 9, weight: .bold))
                    .foregroundStyle(NT.Colors.onPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            case .attended:
                Circle().strokeBorder(NT.Colors.ember, lineWidth: 1.5)
                Image(systemName: "checkmark")
                    .font(.system(size: iconSize, weight: .bold))
                    .foregroundStyle(NT.Colors.ember)
            case .missed, .cancelled:
                Circle().strokeBorder(NT.Colors.bad, lineWidth: 1.5)
                Image(systemName: "xmark")
                    .font(.system(size: iconSize - 1, weight: .bold))
                    .foregroundStyle(NT.Colors.bad)
            }
        }
        .frame(width: size, height: size)
    }

    private var iconSize: CGFloat { (size * 0.43).rounded() }
}
