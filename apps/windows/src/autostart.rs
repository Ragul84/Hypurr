//! Optional "Start with Windows": a per-user Run key (HKCU). Off unless the user turns it on.

#[cfg_attr(not(windows), allow(dead_code))]
pub const VALUE_NAME: &str = "Hypurr";
#[cfg(windows)]
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

#[cfg(windows)]
pub fn is_enabled() -> bool {
    use winreg::{RegKey, enums::HKEY_CURRENT_USER};
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey(RUN_KEY)
        .and_then(|k| k.get_value::<String, _>(VALUE_NAME))
        .is_ok()
}

#[cfg(windows)]
pub fn set(on: bool) -> std::io::Result<()> {
    use winreg::{RegKey, enums::*};
    let (key, _) =
        RegKey::predef(HKEY_CURRENT_USER).create_subkey_with_flags(RUN_KEY, KEY_SET_VALUE)?;
    if on {
        let exe = std::env::current_exe()?;
        key.set_value(
            VALUE_NAME,
            &crate::model::run_key_value(&exe.to_string_lossy()),
        )
    } else {
        match key.delete_value(VALUE_NAME) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            r => r,
        }
    }
}

#[cfg(not(windows))]
pub fn is_enabled() -> bool {
    false
}

#[cfg(not(windows))]
pub fn set(_on: bool) -> std::io::Result<()> {
    Err(std::io::Error::other(
        "Start with Windows is only available on Windows",
    ))
}
