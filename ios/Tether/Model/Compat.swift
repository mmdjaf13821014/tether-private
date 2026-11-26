import Perception
import SwiftUI

// Helpers that let the app run on iOS 16 while using the newer APIs where the system has them.

private struct AppModelKey: EnvironmentKey {
    @MainActor static var defaultValue: AppModel { AppModel.shared }
}

private struct RouterKey: EnvironmentKey {
    @MainActor static var defaultValue: Router { Router.shared }
}

extension EnvironmentValues {
    var appModel: AppModel {
        get { self[AppModelKey.self] }
        set { self[AppModelKey.self] = newValue }
    }
    var router: Router {
        get { self[RouterKey.self] }
        set { self[RouterKey.self] = newValue }
    }
}

extension MainActor {
    /// `MainActor.assumeIsolated` needs iOS 17; the callers are already on the main queue.
    static func assumeIsolatedCompat<T>(_ body: @MainActor @escaping () -> T) -> T {
        if #available(iOS 17, *) { return assumeIsolated(body) }
        return unsafeBitCast(body, to: (() -> T).self)()
    }
}

extension View {
    /// `onChange(of:)` with the new value only (the two-value form needs iOS 17).
    func onChangeCompat<V: Equatable>(of value: V, _ action: @escaping (V) -> Void) -> some View {
        onChange(of: value, perform: action)
    }

    /// A scroll view that starts at its end (`defaultScrollAnchor(.bottom)` on iOS 17).
    @ViewBuilder
    func bottomAnchoredCompat<ID: Hashable>(_ proxy: ScrollViewProxy, last: ID?) -> some View {
        if #available(iOS 17, *) {
            defaultScrollAnchor(.bottom)
        } else {
            onAppear { if let last { proxy.scrollTo(last, anchor: .bottom) } }
        }
    }
}

/// `ContentUnavailableView` (iOS 17) with a plain stand-in for iOS 16.
struct EmptyStateView: View {
    private let title: Text
    private let image: String
    private let detail: Text?
    private let searchText: String?

    init(_ title: LocalizedStringKey, systemImage: String, description: Text? = nil) {
        self.title = Text(title); image = systemImage; detail = description; searchText = nil
    }

    init(_ title: String, systemImage: String, description: Text? = nil) {
        self.title = Text(title); image = systemImage; detail = description; searchText = nil
    }

    init(searchText searchText_: String) {
        title = Text("No Results for “\(searchText_)”"); image = "magnifyingglass"
        detail = Text("Check the spelling or try a new search."); searchText = searchText_
    }

    var body: some View {
        if #available(iOS 17, *) {
            if let searchText { ContentUnavailableView.search(text: searchText) }
            else { ContentUnavailableView { Label { title } icon: { Image(systemName: image) } } description: { detail } }
        } else {
            VStack(spacing: 8) {
                Image(systemName: image).font(.system(size: 44)).foregroundStyle(.secondary)
                title.font(.title2.weight(.bold)).multilineTextAlignment(.center)
                if let detail { detail.font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center) }
            }
            .padding(24)
        }
    }
}
