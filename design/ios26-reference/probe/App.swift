import SwiftUI
import UIKit

// Stripe backdrop: alternating black/white vertical bars, 6pt period, so refraction is visible.
struct Stripes: View {
    var body: some View {
        GeometryReader { g in
            Canvas { ctx, size in
                let p: CGFloat = 6
                var x: CGFloat = 0
                var i = 0
                while x < size.width {
                    ctx.fill(Path(CGRect(x: x, y: 0, width: p/2, height: size.height)),
                             with: .color(i % 2 == 0 ? .white : .white))
                    x += p; i += 1
                }
            }
            .background(Color.black)
            .frame(width: g.size.width, height: g.size.height)
        }
        .ignoresSafeArea()
    }
}

struct Grid: View {
    var body: some View {
        if CommandLine.arguments.contains("solid") { Color(white: 0.35).ignoresSafeArea() } else { gridBody }
    }
    var gridBody: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black))
            var x: CGFloat = 0
            while x < size.width {
                ctx.fill(Path(CGRect(x: x, y: 0, width: 1, height: size.height)), with: .color(x.truncatingRemainder(dividingBy: 50) == 0 ? .red : .white))
                x += 10
            }
            var y: CGFloat = 0
            while y < size.height {
                ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 1)), with: .color(y.truncatingRemainder(dividingBy: 50) == 0 ? .red : .white))
                y += 10
            }
        }.ignoresSafeArea()
    }
}

struct HStripes: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black))
            var y: CGFloat = 0
            while y < size.height { ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 2)), with: .color(.white)); y += 8 }
        }.ignoresSafeArea()
    }
}

struct EdgeBG: View {
    var body: some View {
        GeometryReader { g in
            HStack(spacing: 0) { Color.black; Color.white }
        }.ignoresSafeArea()
    }
}

struct SolidBG: View {
    var c: Color
    var body: some View { c.ignoresSafeArea() }
}

enum Scene: String { case hstripeTabs, hstripePlain, whiteTabs, sheetEdge, grayTabs, edgeTabs, edgePlain, edgeToolbar, tabs, tabsSolid, tabs3, tabs5short, tabsScroll, sheetLarge, toggle, wheel, menu, sheet, alert, dialog, toolbar, plain }

struct TabsScreen: View {
    let solid: Bool
    var body: some View {
        TabView {
            Tab("Today", systemImage: "house.fill") { ZStack { if solid { SolidBG(c: .white) } else { Grid() } } }
            Tab("Plan", systemImage: "calendar") { Color.gray }
            Tab("Log", systemImage: "list.bullet") { Color.gray }
            Tab("Stats", systemImage: "chart.bar.fill") { Color.gray }
            Tab("More", systemImage: "ellipsis") { Color.gray }
        }
    }
}

struct Tabs3Screen: View {
    var body: some View {
        TabView {
            Tab("Today", systemImage: "house.fill") { Grid() }
            Tab("Plan", systemImage: "calendar") { Color.gray }
            Tab("Log", systemImage: "list.bullet") { Color.gray }
        }
    }
}

struct Tabs5Short: View {
    var body: some View {
        TabView {
            Tab("A", systemImage: "1.circle") { Grid() }
            Tab("B", systemImage: "2.circle") { Color.gray }
            Tab("C", systemImage: "3.circle") { Color.gray }
            Tab("D", systemImage: "4.circle") { Color.gray }
            Tab("E", systemImage: "5.circle") { Color.gray }
        }
    }
}

struct TabsScrollScreen: View {
    var body: some View {
        TabView {
            Tab("Today", systemImage: "house.fill") {
                ScrollView { VStack(spacing:0){ ForEach(0..<80) { i in Text("Row \(i)").frame(maxWidth:.infinity, minHeight:40).background(i%2==0 ? Color.white : Color.black).foregroundStyle(i%2==0 ? Color.black : Color.white) } } }
            }
            Tab("Plan", systemImage: "calendar") { Color.gray }
            Tab("Log", systemImage: "list.bullet") { Color.gray }
            Tab("Stats", systemImage: "chart.bar.fill") { Color.gray }
            Tab("More", systemImage: "ellipsis") { Color.gray }
        }
    }
}

struct ToggleScreen: View {
    @State var on = true
    @State var off = false
    var body: some View {
        ZStack {
            Color(white: 0.5).ignoresSafeArea()
            VStack(spacing: 60) {
                Toggle("", isOn: $on).labelsHidden().tint(.white)
                Toggle("", isOn: $off).labelsHidden().tint(.white)
                Toggle("", isOn: .constant(true)).labelsHidden()
            }
        }
    }
}

struct WheelScreen: View {
    @State var d = Date()
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            DatePicker("", selection: $d, displayedComponents: .hourAndMinute)
                .datePickerStyle(.wheel).labelsHidden()
                .background(Color.clear)
        }
    }
}

struct MenuScreen: View {
    var body: some View {
        ZStack {
            Grid()
            VStack {
                Spacer().frame(height: 300)
                Menu("Set type") {
                    Button("Working set") {}
                    Button("Warm-up") {}
                    Button("Drop set") {}
                    Button("Delete", role: .destructive) {}
                }
                .accessibilityIdentifier("menuBtn")
                Spacer()
            }
        }
    }
}

struct SheetScreen: View {
    @State var show = true
    var body: some View {
        ZStack {
            Grid()
            Button("Open") { show = true }.padding(.top, 400)
        }
        .sheet(isPresented: $show) {
            ZStack { Color.clear; Text("Sheet content") }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
}

struct SheetEdgeScreen: View {
    @State var show = true
    var body: some View {
        ZStack { EdgeBG() }
        .sheet(isPresented: $show) { ZStack { Color.clear }.presentationDetents([.medium]).presentationDragIndicator(.visible) }
    }
}

struct SheetLargeScreen: View {
    @State var show = true
    var body: some View {
        ZStack { Grid() }
        .sheet(isPresented: $show) {
            ZStack { Color.clear; Text("Sheet content") }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
    }
}

struct AlertScreen: View {
    @State var show = true
    var body: some View {
        ZStack { Grid(); Button("Open") { show = true }.padding(.top, 400) }
            .alert("Delete workout?", isPresented: $show) {
                Button("Delete", role: .destructive) {}
                Button("Cancel", role: .cancel) {}
            } message: { Text("This cannot be undone.") }
    }
}

struct DialogScreen: View {
    @State var show = true
    var body: some View {
        ZStack { Grid(); Button("Open") { show = true }.padding(.top, 400) }
            .confirmationDialog("Log to", isPresented: $show, titleVisibility: .visible) {
                Button("Breakfast") {}
                Button("Lunch") {}
                Button("Dinner") {}
                Button("Delete", role: .destructive) {}
            }
    }
}

struct ToolbarScreen: View {
    var body: some View {
        NavigationStack {
            ScrollView { Grid().frame(height: 1500) }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button { } label: { Image(systemName: "chevron.left") } }
                    ToolbarItem(placement: .topBarTrailing) { Button { } label: { Image(systemName: "square.and.arrow.up") } }
                    ToolbarItem(placement: .topBarTrailing) { Button { } label: { Image(systemName: "ellipsis") } }
                    ToolbarItem(placement: .topBarTrailing) { Button("Done") { } }
                }
                .navigationTitle("Title")
        }
    }
}

@main
struct App_: App {
    init() {
        let a = UITabBarAppearance()
        a.configureWithOpaqueBackground()
        a.backgroundColor = UIColor(red: 0.086, green: 0.086, blue: 0.094, alpha: 1)
        a.shadowColor = UIColor(white: 1, alpha: 0.08)
        if CommandLine.arguments.contains("tabsSolid") {
            UITabBar.appearance().standardAppearance = a
            UITabBar.appearance().scrollEdgeAppearance = a
        }
    }
    var body: some SwiftUI.Scene {
        WindowGroup {
            content
                .preferredColorScheme(.dark)
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        if let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first {
                            dumpProbe(w)
                        }
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
                        var out = ""
                        for sc in UIApplication.shared.connectedScenes {
                            guard let ws = sc as? UIWindowScene else { continue }
                            for w in ws.windows { out += "== WINDOW \(type(of: w)) \(w.frame) level=\(w.windowLevel.rawValue)\n"; dumpTree(w, 0, &out) }
                        }
                        try? out.write(toFile: NSTemporaryDirectory()+"/tree.txt", atomically: true, encoding: .utf8)
                        print("TREEWRITTEN \(out.count)")
                    }
                }
        }
    }
    @ViewBuilder var content: some View {
        let s = Scene(rawValue: CommandLine.arguments.last ?? "") ?? .tabs
        switch s {
        case .hstripeTabs: TabView { Tab("Today", systemImage: "house.fill") { HStripes() }; Tab("Plan", systemImage: "calendar") { HStripes() }; Tab("Log", systemImage: "list.bullet") { HStripes() } }
        case .hstripePlain: HStripes()
        case .whiteTabs: TabView { Tab("Today", systemImage: "house.fill") { GeometryReader { g in Color.white.ignoresSafeArea().onAppear { print("PROBE tabContentSafeArea=\(g.safeAreaInsets)") } } }; Tab("Plan", systemImage: "calendar") { Color.white }; Tab("Log", systemImage: "list.bullet") { Color.white } }
        case .sheetEdge: SheetEdgeScreen()
        case .grayTabs: TabView { Tab("Today", systemImage: "house.fill") { Color(white: 0.5).ignoresSafeArea() }; Tab("Plan", systemImage: "calendar") { Color.gray }; Tab("Log", systemImage: "list.bullet") { Color.gray }; Tab("Stats", systemImage: "chart.bar.fill") { Color.gray }; Tab("More", systemImage: "ellipsis") { Color.gray } }
        case .edgeTabs: TabView { Tab("Today", systemImage: "house.fill") { EdgeBG() }; Tab("Plan", systemImage: "calendar") { EdgeBG() }; Tab("Log", systemImage: "list.bullet") { EdgeBG() }; Tab("Stats", systemImage: "chart.bar.fill") { EdgeBG() }; Tab("More", systemImage: "ellipsis") { EdgeBG() } }
        case .edgePlain: EdgeBG()
        case .edgeToolbar: NavigationStack { ScrollView { EdgeBG().frame(height: 1500) }.toolbar { ToolbarItem(placement: .topBarTrailing) { Button { } label: { Image(systemName: "square.and.arrow.up") } } }.navigationTitle("T") }
        case .tabs: TabsScreen(solid: false)
        case .tabsSolid: TabsScreen(solid: false)
        case .tabs3: Tabs3Screen()
        case .tabs5short: Tabs5Short()
        case .tabsScroll: TabsScrollScreen()
        case .toggle: ToggleScreen()
        case .wheel: WheelScreen()
        case .menu: MenuScreen()
        case .sheet: SheetScreen()
        case .sheetLarge: SheetLargeScreen()
        case .alert: AlertScreen()
        case .dialog: DialogScreen()
        case .toolbar: ToolbarScreen()
        case .plain: Grid()
        }
    }
}
