import UIKit
import SwiftUI

func dumpProbe(_ win: UIWindow) {
    var s = ""
    func p(_ x: String) { s += x + "\n"; print("PROBE " + x) }
    p("screen.bounds=\(UIScreen.main.bounds) scale=\(UIScreen.main.scale)")
    p("safeAreaInsets=\(win.safeAreaInsets)")
    if let r = UIScreen.main.value(forKey: "_displayCornerRadius") as? CGFloat { p("displayCornerRadius=\(r)") }
    let sw = UISwitch()
    p("UISwitch.intrinsic=\(sw.intrinsicContentSize) bounds=\(sw.bounds) frame=\(sw.frame)")
    sw.sizeToFit(); p("UISwitch.sizeToFit=\(sw.frame)")
    let dp = UIDatePicker(); dp.datePickerMode = .time; dp.preferredDatePickerStyle = .wheels
    p("UIDatePicker.wheels.intrinsic=\(dp.intrinsicContentSize)")
    let pk = UIPickerView(); p("UIPickerView.intrinsic=\(pk.intrinsicContentSize)")
    let tbc = UITabBarController()
    p("tabBarMinimizeBehavior.default=\(tbc.tabBarMinimizeBehavior.rawValue)")
    p("UITabBar.default.frame=\(tbc.tabBar.frame) isTranslucent=\(tbc.tabBar.isTranslucent)")
    let seg = UISegmentedControl(items: ["a","b"]); p("UISegmentedControl.intrinsic=\(seg.intrinsicContentSize)")
    let btn = UIButton(configuration: .glass()); btn.setTitle("Done", for: .normal); btn.sizeToFit()
    p("UIButton.glass.sizeToFit=\(btn.frame)")
    let sl = UISlider(); p("UISlider.intrinsic=\(sl.intrinsicContentSize)")
    // sheet corner radius default
    p("UISheetPresentationController.exists")
    try? s.write(toFile: NSTemporaryDirectory()+"/probe.txt", atomically: true, encoding: .utf8)
}

// live probe of a presented hierarchy
func dumpTree(_ v: UIView, _ depth: Int = 0, _ out: inout String) {
    let ind = String(repeating: "  ", count: depth)
    let cls = String(describing: type(of: v))
    out += "\(ind)\(cls) f=\(v.frame) r=\(v.layer.cornerRadius) alpha=\(v.alpha) bg=\(v.backgroundColor?.description ?? "-")\n"
    if depth < 16 { for s in v.subviews { dumpTree(s, depth+1, &out) } }
}
