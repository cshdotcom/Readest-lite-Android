// Readest Lite shell - minimal Rust backend.
// All heavy lifting (WebView config, file upload/download, clipboard,
// back button, deep link) is handled in Kotlin (MainActivity.kt).
//
// Commands below are exposed to JS via the Tauri bridge, but the remote
// reader page (configured in MainActivity.kt's HOME_URL constant) is not
// expected to call them. They exist for future use and to keep the Tauri
// runtime happy.

/// Clear WebView HTTP cache. Does NOT touch localStorage / IndexedDB,
/// so login session and reading preferences are preserved.
#[tauri::command]
fn clear_cache() -> bool {
    // The actual clearing is done on the Kotlin side via WebView.clearCache(true).
    // This command is a marker; if invoked, it dispatches an event the
    // MainActivity listens for. For now it just returns true.
    true
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![clear_cache])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
