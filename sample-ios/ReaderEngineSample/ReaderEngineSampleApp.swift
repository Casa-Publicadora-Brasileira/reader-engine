import SwiftUI

@main
struct ReaderEngineSampleApp: App {
    @StateObject private var viewModel = ReaderViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
                .onOpenURL { url in
                    viewModel.importFromDevice(url: url, hintExtension: nil)
                }
        }
    }
}
